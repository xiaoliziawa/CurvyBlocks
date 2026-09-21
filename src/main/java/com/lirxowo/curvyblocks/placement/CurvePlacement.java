package com.lirxowo.curvyblocks.placement;

import com.lirxowo.curvyblocks.CurvyBlocks;
import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.geometry.CurveBend;
import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.lirxowo.curvyblocks.physics.CurvePhysics;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CurvePlacement {
    public static final TagKey<Block> NO_OVERLAP = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(CurvyBlocks.MODID, "no_overlap"));

    private CurvePlacement() {
    }

    public static PlacementResult validate(Level level, Player player, Curve curve) {
        if (!player.mayBuild() || player.isSpectator() || curve.material().isAir()) {
            return PlacementResult.DENIED;
        }
        if (curve.geometry().length() > CurveConfig.MAX_LENGTH.get()) {
            return PlacementResult.TOO_LONG;
        }
        double range = CurveConfig.PLACEMENT_RANGE.get();
        Vec3 eye = player.getEyePosition();
        for (CurvePoint point : curve.points()) {
            if (point.position().distanceToSqr(eye) > range * range) {
                return PlacementResult.TOO_FAR;
            }
        }
        if (!curve.bends().isEmpty()) {
            double bendLimit = CurveConfig.AUTO_ROUTE_SEARCH_MARGIN.get();
            for (CurveBend bend : curve.bends()) {
                if (bend.offset().lengthSqr() > bendLimit * bendLimit + CurveLimits.EPSILON) {
                    return PlacementResult.INVALID_SHAPE;
                }
            }
            for (CurveGeometry.Sample sample : curve.geometry().samples()) {
                if (sample.position().distanceToSqr(eye) > range * range) {
                    return PlacementResult.TOO_FAR;
                }
            }
        }
        AABB bounds = curve.geometry().bounds();
        if (bounds.minY < level.getMinBuildHeight() || bounds.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ))
                || !level.getWorldBorder().isWithinBounds(BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
            return PlacementResult.OUTSIDE_WORLD;
        }
        for (int x = CurveIndex.chunk(bounds.minX); x <= CurveIndex.chunk(bounds.maxX); x++) {
            for (int z = CurveIndex.chunk(bounds.minZ); z <= CurveIndex.chunk(bounds.maxZ); z++) {
                if (!level.hasChunk(x, z)) {
                    return PlacementResult.UNLOADED;
                }
            }
        }
        if (new CurveObstacles(level).firstIntersection(curve.geometry()) >= 0) {
            return PlacementResult.INTERSECTS_BLOCK;
        }
        if (CurveConfig.SOLID_CURVES.get() && CurvePhysics.hasCollision(curve.material())) {
            for (Entity entity : level.getEntities((Entity) null, bounds, candidate -> candidate.isAlive() && !candidate.isSpectator())) {
                if (!entity.isPickable()) {
                    continue;
                }
                for (CurveGeometry.Segment segment : curve.geometry().segments()) {
                    if (segment.bounds().intersects(entity.getBoundingBox())) {
                        return PlacementResult.INTERSECTS_ENTITY;
                    }
                }
            }
        }
        return PlacementResult.OK;
    }

    public static LongSet occupiedBlocks(Curve curve) {
        LongSet positions = new LongOpenHashSet();
        for (CurveGeometry.Segment segment : curve.geometry().segments()) {
            AABB box = segment.bounds().deflate(CurveLimits.EPSILON);
            for (int x = Mth.floor(box.minX); x <= Mth.floor(box.maxX); x++) {
                for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
                    for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                        positions.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        return positions;
    }
}
