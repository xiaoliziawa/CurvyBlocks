package com.lirxowo.curvyblocks.placement;

import java.util.ArrayList;
import java.util.List;

import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class CurveObstacles {
    private static final int SHAPE_NEIGHBORHOOD = 1;
    private final Level level;
    private final boolean allowOverlap;
    private final Long2ObjectMap<List<AABB>> boxes = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

    public CurveObstacles(Level level) {
        this.level = level;
        allowOverlap = CurveConfig.ALLOW_BLOCK_OVERLAP.get();
    }

    public int firstIntersection(CurveGeometry geometry) {
        List<CurveGeometry.Segment> segments = geometry.segments();
        for (int i = 0; i < segments.size(); i++) {
            if (firstObstacle(segments.get(i).bounds().deflate(CurveLimits.EPSILON)) != null) {
                return i;
            }
        }
        return -1;
    }

    public AABB firstObstacle(AABB bounds) {
        if (!available(bounds)) {
            return bounds;
        }
        int minX = Mth.floor(bounds.minX) - SHAPE_NEIGHBORHOOD;
        int minY = Mth.floor(bounds.minY) - SHAPE_NEIGHBORHOOD;
        int minZ = Mth.floor(bounds.minZ) - SHAPE_NEIGHBORHOOD;
        int maxX = Mth.floor(bounds.maxX) + SHAPE_NEIGHBORHOOD;
        int maxY = Mth.floor(bounds.maxY) + SHAPE_NEIGHBORHOOD;
        int maxZ = Mth.floor(bounds.maxZ) + SHAPE_NEIGHBORHOOD;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    position.set(x, y, z);
                    long key = position.asLong();
                    List<AABB> obstacles = boxes.get(key);
                    if (obstacles == null) {
                        obstacles = blockBoxes();
                        boxes.put(key, obstacles);
                    }
                    for (AABB obstacle : obstacles) {
                        if (bounds.intersects(obstacle)) {
                            return obstacle;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean available(AABB bounds) {
        if (bounds.minY < level.getMinBuildHeight() || bounds.maxY > level.getMaxBuildHeight()) {
            return false;
        }
        position.set(Mth.floor(bounds.minX), Mth.floor(bounds.minY), Mth.floor(bounds.minZ));
        if (!level.getWorldBorder().isWithinBounds(position)) {
            return false;
        }
        position.set(Mth.floor(bounds.maxX), Mth.floor(bounds.maxY), Mth.floor(bounds.maxZ));
        if (!level.getWorldBorder().isWithinBounds(position)) {
            return false;
        }
        int minChunkX = Mth.floor(bounds.minX) >> 4;
        int maxChunkX = Mth.floor(bounds.maxX) >> 4;
        int minChunkZ = Mth.floor(bounds.minZ) >> 4;
        int maxChunkZ = Mth.floor(bounds.maxZ) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (!level.hasChunk(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<AABB> blockBoxes() {
        if (!level.hasChunkAt(position)) {
            return List.of();
        }
        BlockState state = level.getBlockState(position);
        if (state.isAir()) {
            return List.of();
        }
        if (state.is(CurvePlacement.NO_OVERLAP)) {
            return List.of(new AABB(position));
        }
        if (allowOverlap) {
            return List.of();
        }
        List<AABB> localBoxes = state.getCollisionShape(level, position, CollisionContext.empty()).toAabbs();
        if (localBoxes.isEmpty()) {
            return List.of();
        }
        List<AABB> worldBoxes = new ArrayList<>(localBoxes.size());
        for (AABB box : localBoxes) {
            worldBoxes.add(box.move(position));
        }
        return worldBoxes;
    }
}
