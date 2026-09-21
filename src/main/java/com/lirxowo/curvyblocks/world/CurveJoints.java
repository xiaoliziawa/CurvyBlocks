package com.lirxowo.curvyblocks.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurveMath;
import com.lirxowo.curvyblocks.geometry.CurvePoint;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CurveJoints {
    private static final double ANCHOR_TOLERANCE = 1.0 / 256.0;
    private static final double MERGE_DISTANCE_SQUARED = ANCHOR_TOLERANCE * ANCHOR_TOLERANCE;
    private static final Comparator<CurveKnot> KNOT_ORDER = Comparator.comparingDouble((CurveKnot knot) -> knot.position().x)
            .thenComparingDouble(knot -> knot.position().y).thenComparingDouble(knot -> knot.position().z);
    private final CurveIndex index;
    private final ParentSearch parentSearch = new ParentSearch();
    private final Long2ObjectMap<List<CurveKnot>> byParent = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<List<CurveKnot>> chunks = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<List<CurveKnot>> cells = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<AABB> visibleBounds = new Long2ObjectOpenHashMap<>();
    private long indexedRevision = -1L;
    private long revision;
    private double scale = Double.NaN;

    CurveJoints(CurveIndex index) {
        this.index = index;
    }

    public long revision() {
        refresh();
        return revision;
    }

    public List<CurveKnot> forCurve(long id) {
        refresh();
        List<CurveKnot> knots = byParent.get(id);
        return knots == null ? List.of() : knots;
    }

    public AABB bounds(Curve curve) {
        refresh();
        AABB bounds = visibleBounds.get(curve.id());
        return bounds == null ? curve.geometry().bounds() : bounds;
    }

    public boolean validAnchors(List<CurvePoint> points) {
        for (CurvePoint point : points) {
            if (point.parentId() > 0L) {
                Curve parent = index.get(point.parentId());
                if (parent == null || !parent.geometry().containsCenterline(point.position(), ANCHOR_TOLERANCE)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Curve parent(Curve child, CurvePoint point) {
        if (point.parentId() > 0L) {
            return point.parentId() == child.id() ? null : index.get(point.parentId());
        }
        if (child.id() <= 0L || point.normal().lengthSqr() < CurveLimits.EPSILON) {
            return null;
        }
        parentSearch.childId = child.id();
        parentSearch.position = point.position();
        parentSearch.found = null;
        Vec3 position = point.position();
        index.visit(new AABB(position.x - ANCHOR_TOLERANCE, position.y - ANCHOR_TOLERANCE, position.z - ANCHOR_TOLERANCE,
                position.x + ANCHOR_TOLERANCE, position.y + ANCHOR_TOLERANCE, position.z + ANCHOR_TOLERANCE), parentSearch);
        return parentSearch.found;
    }

    private void collect(Curve child, Merger merger) {
        if (child.section() != CrossSection.ROUND) {
            return;
        }
        double childRadius = child.section().outerRadius(child.diameter());
        for (CurvePoint point : child.points()) {
            Curve parent = parent(child, point);
            if (parent != null && parent.section() == CrossSection.ROUND) {
                double radius = (Math.max(childRadius, parent.section().outerRadius(parent.diameter())) + ANCHOR_TOLERANCE) * scale;
                merger.add(parent.id(), child.id(), point.position(), radius);
            }
        }
    }

    private void refresh() {
        double nextScale = CurveConfig.BRANCH_KNOT_SCALE.get();
        if (indexedRevision == index.revision() && scale == nextScale) {
            return;
        }
        clear();
        indexedRevision = index.revision();
        scale = nextScale;
        Merger merger = new Merger();
        for (Curve curve : index.all()) {
            collect(curve, merger);
        }
        for (CurveKnot knot : merger.finish()) {
            add(byParent, knot.parentId(), knot);
            add(cells, knot.lightPosition().asLong(), knot);
            AABB bounds = knot.bounds();
            for (int x = CurveIndex.chunk(bounds.minX); x <= CurveIndex.chunk(bounds.maxX); x++) {
                for (int z = CurveIndex.chunk(bounds.minZ); z <= CurveIndex.chunk(bounds.maxZ); z++) {
                    add(chunks, ChunkPos.asLong(x, z), knot);
                }
            }
        }
        freeze(byParent);
        for (Long2ObjectMap.Entry<List<CurveKnot>> entry : byParent.long2ObjectEntrySet()) {
            AABB bounds = index.get(entry.getLongKey()).geometry().bounds();
            for (CurveKnot knot : entry.getValue()) {
                bounds = bounds.minmax(knot.bounds());
            }
            visibleBounds.put(entry.getLongKey(), bounds);
        }
    }

    public Long2ObjectMap<List<CurveKnot>> preview(Curve draft) {
        refresh();
        Merger merger = new Merger();
        collect(draft, merger);
        Long2ObjectMap<List<CurveKnot>> result = new Long2ObjectOpenHashMap<>();
        for (CurveKnot knot : merger.finish()) {
            CurveKnot existing = nearest(knot.position());
            if (existing != null) {
                CurveKnot owner = existing.parentId() <= knot.parentId() ? existing : knot;
                knot = new CurveKnot(owner.parentId(), owner.position(), Math.max(existing.radius(), knot.radius()));
            }
            add(result, knot.parentId(), knot);
        }
        freeze(result);
        return result;
    }

    private CurveKnot nearest(Vec3 position) {
        CurveKnot closest = null;
        double distance = MERGE_DISTANCE_SQUARED;
        int maxX = Mth.floor(position.x + ANCHOR_TOLERANCE);
        int maxY = Mth.floor(position.y + ANCHOR_TOLERANCE);
        int maxZ = Mth.floor(position.z + ANCHOR_TOLERANCE);
        for (int x = Mth.floor(position.x - ANCHOR_TOLERANCE); x <= maxX; x++) {
            for (int y = Mth.floor(position.y - ANCHOR_TOLERANCE); y <= maxY; y++) {
                for (int z = Mth.floor(position.z - ANCHOR_TOLERANCE); z <= maxZ; z++) {
                    List<CurveKnot> bucket = cells.get(BlockPos.asLong(x, y, z));
                    if (bucket == null) {
                        continue;
                    }
                    for (CurveKnot knot : bucket) {
                        double next = knot.position().distanceToSqr(position);
                        if (next <= distance) {
                            distance = next;
                            closest = knot;
                        }
                    }
                }
            }
        }
        return closest;
    }

    CurveIndex.Hit pick(Vec3 origin, Vec3 direction, double range) {
        refresh();
        if (chunks.isEmpty()) {
            return null;
        }
        double endX = origin.x + direction.x * range;
        double endZ = origin.z + direction.z * range;
        int minX = CurveIndex.chunk(Math.min(origin.x, endX));
        int minZ = CurveIndex.chunk(Math.min(origin.z, endZ));
        int maxX = CurveIndex.chunk(Math.max(origin.x, endX));
        int maxZ = CurveIndex.chunk(Math.max(origin.z, endZ));
        CurveKnot closest = null;
        double distance = range;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                List<CurveKnot> bucket = chunks.get(ChunkPos.asLong(x, z));
                if (bucket == null) {
                    continue;
                }
                for (CurveKnot knot : bucket) {
                    if (!CurveIndex.firstChunk(knot.bounds(), minX, minZ, x, z)) {
                        continue;
                    }
                    double next = CurveMath.raySphere(origin, direction, knot.position(), knot.radius());
                    if (next <= distance) {
                        distance = next;
                        closest = knot;
                    }
                }
            }
        }
        return closest == null ? null : new CurveIndex.Hit(index.get(closest.parentId()), origin.add(direction.scale(distance)), distance, closest);
    }

    void clear() {
        byParent.clear();
        chunks.clear();
        cells.clear();
        visibleBounds.clear();
        parentSearch.found = null;
        parentSearch.position = null;
        indexedRevision = -1L;
        revision++;
    }

    private static void add(Long2ObjectMap<List<CurveKnot>> groups, long key, CurveKnot knot) {
        groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(knot);
    }

    private static void freeze(Long2ObjectMap<List<CurveKnot>> groups) {
        for (Long2ObjectMap.Entry<List<CurveKnot>> entry : groups.long2ObjectEntrySet()) {
            entry.getValue().sort(KNOT_ORDER);
            entry.setValue(List.copyOf(entry.getValue()));
        }
    }

    private static final class ParentSearch implements Consumer<Curve> {
        private long childId;
        private Vec3 position;
        private Curve found;

        @Override
        public void accept(Curve candidate) {
            if (candidate.id() < childId && (found == null || candidate.id() < found.id())
                    && candidate.geometry().containsCenterline(position, ANCHOR_TOLERANCE)) {
                found = candidate;
            }
        }
    }

    private static final class Merger {
        private final Long2ObjectMap<List<KnotBuilder>> cells = new Long2ObjectOpenHashMap<>();
        private final List<KnotBuilder> knots = new ArrayList<>();

        private void add(long parentId, long childId, Vec3 position, double radius) {
            KnotBuilder existing = find(position);
            if (existing == null) {
                KnotBuilder created = new KnotBuilder(parentId, childId, position, radius);
                knots.add(created);
                cells.computeIfAbsent(cell(position), ignored -> new ArrayList<>()).add(created);
                return;
            }
            existing.radius = Math.max(existing.radius, radius);
            if (parentId < existing.parentId || parentId == existing.parentId && childId < existing.childId) {
                long oldCell = cell(existing.position);
                long newCell = cell(position);
                existing.parentId = parentId;
                existing.childId = childId;
                existing.position = position;
                if (oldCell != newCell) {
                    cells.get(oldCell).remove(existing);
                    cells.computeIfAbsent(newCell, ignored -> new ArrayList<>()).add(existing);
                }
            }
        }

        private KnotBuilder find(Vec3 position) {
            int maxX = Mth.floor(position.x + ANCHOR_TOLERANCE);
            int maxY = Mth.floor(position.y + ANCHOR_TOLERANCE);
            int maxZ = Mth.floor(position.z + ANCHOR_TOLERANCE);
            for (int x = Mth.floor(position.x - ANCHOR_TOLERANCE); x <= maxX; x++) {
                for (int y = Mth.floor(position.y - ANCHOR_TOLERANCE); y <= maxY; y++) {
                    for (int z = Mth.floor(position.z - ANCHOR_TOLERANCE); z <= maxZ; z++) {
                        List<KnotBuilder> bucket = cells.get(BlockPos.asLong(x, y, z));
                        if (bucket != null) {
                            for (KnotBuilder knot : bucket) {
                                if (knot.position.distanceToSqr(position) <= MERGE_DISTANCE_SQUARED) {
                                    return knot;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        private List<CurveKnot> finish() {
            List<CurveKnot> result = new ArrayList<>(knots.size());
            for (KnotBuilder knot : knots) {
                result.add(new CurveKnot(knot.parentId, knot.position, knot.radius));
            }
            return result;
        }

        private static long cell(Vec3 position) {
            return BlockPos.asLong(Mth.floor(position.x), Mth.floor(position.y), Mth.floor(position.z));
        }
    }

    private static final class KnotBuilder {
        private long parentId;
        private long childId;
        private Vec3 position;
        private double radius;

        private KnotBuilder(long parentId, long childId, Vec3 position, double radius) {
            this.parentId = parentId;
            this.childId = childId;
            this.position = position;
            this.radius = radius;
        }
    }
}
