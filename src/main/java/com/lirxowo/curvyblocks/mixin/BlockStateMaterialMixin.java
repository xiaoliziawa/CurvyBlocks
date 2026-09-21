package com.lirxowo.curvyblocks.mixin;

import com.lirxowo.curvyblocks.physics.CurvePhysics;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.neoforged.neoforge.common.extensions.IBlockStateExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = IBlockStateExtension.class, remap = false)
public interface BlockStateMaterialMixin {
    @ModifyReturnValue(method = "getFriction", at = @At("RETURN"))
    private float curvyblocks$friction(float original, LevelReader level, BlockPos position, Entity entity) {
        return CurvePhysics.friction(original, level, position, entity);
    }
}
