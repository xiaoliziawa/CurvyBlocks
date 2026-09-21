package com.lirxowo.curvyblocks.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurveMath;
import com.lirxowo.curvyblocks.physics.CurvePhysics;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class CurveIndex {
    private final Long2ObjectMap<Curve> curves = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Long2ObjectMap<Curve>> chunks = new Long2ObjectOpenHashMap<>();
    private final CurveJoints joints = new CurveJoints(this);
    private long revision;

    public void put(Curve curve) {
        remove(curve.id());
        curves.put(curve.id(), curve);
        revision++;
        AABB bounds = curve.geometry().bounds();
        for (int x = chunk(bounds.minX); x <= chunk(bounds.maxX); x++) {
            for (int z = chunk(bounds.minZ); z <= chunk(bounds.maxZ); z++) {
                chunks.computeIfAbsent(ChunkPos.asLong(x, z), key -> new Long2ObjectOpenHashMap<>())
                        .put(curve.id(), curve);
            }
        }
    }

    public Curve remove(long id) {
        Curve curve = curves.remove(id);
        if (curve == null) {
            return null;
        }
        revision++;
        AABB bounds = curve.geometry().bounds();
        for (int x = chunk(bounds.minX); x <= chunk(bounds.maxX); x++) {
            for (int z = chunk(bounds.minZ); z <= chunk(bounds.maxZ); z++) {
                long key = ChunkPos.asLong(x, z);
                Long2ObjectMap<Curve> bucket = chunks.get(key);
                if (bucket != null) {
                    bucket.remove(id);
                    if (bucket.isEmpty()) {
                        chunks.remove(key);
                    }
                }
            }
        }
        return curve;
    }

    public Curve get(long id) {
        return curves.get(id);
    }

    public Collection<Curve> all() {
        return curves.values();
    }

    public Collection<Curve> inChunk(long key) {
        Long2ObjectMap<Curve> bucket = chunks.get(key);
        return bucket == null ? List.of() : bucket.values();
    }

    public boolean isEmpty() {
        return curves.isEmpty();
    }

    public void clear() {
        curves.clear();
        chunks.clear();
        joints.clear();
        revision++;
    }

    public long revision() {
        return revision;
    }

    public CurveJoints joints() {
        return joints;
    }

    public void visit(AABB box, Consumer<Curve> visitor) {
        int minX = chunk(box.minX);
        int minZ = chunk(box.minZ);
        int maxX = chunk(box.maxX);
        int maxZ = chunk(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Long2ObjectMap<Curve> bucket = chunks.get(ChunkPos.asLong(x, z));
                if (bucket == null) {
                    continue;
                }
                for (Curve curve : bucket.values()) {
                    AABB bounds = curve.geometry().bounds();
                    if (firstChunk(bounds, minX, minZ, x, z) && bounds.intersects(box)) {
                        visitor.accept(curve);
                    }
                }
            }
        }
    }

    public List<VoxelShape> collisions(AABB box, List<VoxelShape> existing) {
        List<VoxelShape> result = existing;
        int minX = chunk(box.minX);
        int minZ = chunk(box.minZ);
        int maxX = chunk(box.maxX);
        int maxZ = chunk(box.maxZ);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Long2ObjectMap<Curve> bucket = chunks.get(ChunkPos.asLong(x, z));
                if (bucket == null) {
                    continue;
                }
                for (Curve curve : bucket.values()) {
                    AABB bounds = curve.geometry().bounds();
                    if (!firstChunk(bounds, minX, minZ, x, z) || !bounds.intersects(box)
                            || !CurvePhysics.hasCollision(curve.material())) {
                        continue;
                    }
                    for (CurveGeometry.Segment segment : curve.geometry().segments()) {
                        if (segment.bounds().intersects(box)) {
                            if (result == existing) {
                                result = new ArrayList<>(existing);
                            }
                            result.add(segment.shape());
                        }
                    }
                }
            }
        }
        return result;
    }

    public Hit pick(Vec3 origin, Vec3 direction, double range) {
        if (curves.isEmpty() || range <= 0.0) {
            return null;
        }
        Vec3 end = origin.add(direction.scale(range));
        int minX = chunk(Math.min(origin.x, end.x) - CurveLimits.PICK_PADDING);
        int minZ = chunk(Math.min(origin.z, end.z) - CurveLimits.PICK_PADDING);
        int maxX = chunk(Math.max(origin.x, end.x) + CurveLimits.PICK_PADDING);
        int maxZ = chunk(Math.max(origin.z, end.z) + CurveLimits.PICK_PADDING);
        Curve closest = null;
        double distance = range;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Long2ObjectMap<Curve> bucket = chunks.get(ChunkPos.asLong(x, z));
                if (bucket == null) {
                    continue;
                }
                for (Curve curve : bucket.values()) {
                    AABB bounds = curve.geometry().bounds();
                    if (!firstChunk(bounds, minX, minZ, x, z)
                            || CurveMath.rayBox(origin, direction, bounds, distance) > distance) {
                        continue;
                    }
                    for (CurveGeometry.Segment segment : curve.geometry().segments()) {
                        double hit = CurveMath.rayBox(origin, direction, segment.bounds(), distance);
                        if (hit <= distance) {
                            distance = hit;
                            closest = curve;
                        }
                    }
                }
            }
        }
        Hit knot = joints.pick(origin, direction, distance);
        if (knot != null) {
            return knot;
        }
        return closest == null ? null : new Hit(closest, origin.add(direction.scale(distance)), distance);
    }

    static boolean firstChunk(AABB bounds, int minX, int minZ, int x, int z) {
        return x == Math.max(minX, chunk(bounds.minX)) && z == Math.max(minZ, chunk(bounds.minZ));
    }

    public static int chunk(double coordinate) {
        return Mth.floor(coordinate) >> 4;
    }

    public record Hit(Curve curve, Vec3 position, double distance, CurveKnot knot) {
        public Hit(Curve curve, Vec3 position, double distance) {
            this(curve, position, distance, null);
        }
    }
}
