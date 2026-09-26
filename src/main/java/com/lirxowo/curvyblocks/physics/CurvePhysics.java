package com.lirxowo.curvyblocks.physics;

import java.util.List;

import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.mixin.BlockCollisionAccess;
import com.lirxowo.curvyblocks.physics.CurveBlockEffects.Effect;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.lirxowo.curvyblocks.world.CurveWorlds;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class CurvePhysics {
    private CurvePhysics() {
    }

    public static boolean hasCollision(BlockState material) {
        return !CurveConfig.MATERIAL_EFFECTS.get() || ((BlockCollisionAccess) material.getBlock()).curvyblocks$hasCollision();
    }

    private static CurveContactCache contacts(Entity entity) {
        if (entity == null || entity.noPhysics || entity.isRemoved() || entity.isSpectator()) {
            return null;
        }
        CurveIndex index = CurveWorlds.get(entity.level());
        if (index == null || index.isEmpty() || !CurveConfig.SOLID_CURVES.get() || !CurveConfig.MATERIAL_EFFECTS.get()) {
            return null;
        }
        CurveContactCache contacts = ((CurveEntityAccess) entity).curvyblocks$contacts();
        contacts.refresh(entity, index);
        return contacts;
    }

    public static CurveContact support(Entity entity) {
        CurveContactCache contacts = contacts(entity);
        return contacts == null ? null : contacts.support();
    }

    public static BlockState supportState(Entity entity, BlockState original) {
        CurveContact support = support(entity);
        return support == null ? original : support.material();
    }

    public static BlockState bodyState(Entity entity, BlockState original) {
        if (!original.isAir()) {
            return original;
        }
        CurveContactCache contacts = contacts(entity);
        return contacts == null || contacts.body() == null ? original : contacts.body().material();
    }

    public static BlockPos supportPosition(Entity entity, BlockPos original) {
        CurveContact support = support(entity);
        return support == null ? original : support.position();
    }

    public static float friction(float original, LevelReader level, BlockPos position, Entity entity) {
        if (entity == null || entity.level() != level || CurveBlockEffects.active()) {
            return original;
        }
        CurveContact support = support(entity);
        return support != null && queriesSupport(entity, support, position)
                ? CurveBlockEffects.friction(support, entity) : original;
    }

    private static boolean queriesSupport(Entity entity, CurveContact support, BlockPos position) {
        if (position.equals(support.position())) {
            return true;
        }
        BlockPos entityPosition = entity.blockPosition();
        return position.getX() == entityPosition.getX() && position.getZ() == entityPosition.getZ()
                && position.getY() == entityPosition.getY() - 1;
    }

    public static float groundFriction(Entity entity, float original) {
        if (CurveBlockEffects.active()) {
            return original;
        }
        CurveContact support = support(entity);
        return support == null ? original : CurveBlockEffects.friction(support, entity);
    }

    public static boolean stepOn(Entity entity) {
        if (CurveBlockEffects.active()) {
            return false;
        }
        CurveContactCache contacts = contacts(entity);
        CurveContact support = contacts == null ? null : contacts.support();
        if (support == null) {
            return false;
        }
        if (contacts.beginStepEffect(support.curve().id(), entity.tickCount)) {
            CurveBlockEffects.apply(Effect.STEP, support, entity, 0.0F);
        }
        return true;
    }

    public static boolean fallOn(Entity entity, float fallDistance) {
        if (CurveBlockEffects.active()) {
            return false;
        }
        CurveContact support = support(entity);
        if (support == null) {
            return false;
        }
        if (!CurveBlockEffects.apply(Effect.FALL, support, entity, fallDistance)) {
            Blocks.AIR.fallOn(entity.level(), support.material(), support.position(), entity, fallDistance);
        }
        return true;
    }

    public static boolean landOn(Entity entity) {
        if (!entity.verticalCollisionBelow || CurveBlockEffects.active()) {
            return false;
        }
        CurveContact support = support(entity);
        if (support == null) {
            return false;
        }
        if (!CurveBlockEffects.apply(Effect.LAND, support, entity, 0.0F)) {
            Blocks.AIR.updateEntityAfterFallOn(entity.level(), entity);
        }
        return true;
    }

    public static void inside(Entity entity) {
        if (CurveBlockEffects.active()) {
            return;
        }
        CurveContactCache contacts = contacts(entity);
        if (contacts == null || contacts.touching().isEmpty()) {
            return;
        }
        List<CurveContact> touching = contacts.touching();
        Level level = entity.level();
        AABB bounds = entity.getBoundingBox();
        contacts.setApplyingEffects(true);
        try {
            for (CurveContact curveContact : touching) {
                if (!entity.isAlive() || entity.level() != level || entity.getBoundingBox() != bounds) {
                    break;
                }
                if (contacts.beginInsideEffect(curveContact.curve().id(), entity.tickCount)
                        && CurveBlockEffects.apply(Effect.INSIDE, curveContact, entity, 0.0F)
                        && entity.isAlive() && entity.level() == level && entity.getBoundingBox() == bounds) {
                    ((CurveEntityAccess) entity).curvyblocks$onInsideBlock(curveContact.material());
                }
            }
        } finally {
            contacts.setApplyingEffects(false);
        }
    }
}
