package com.lirxowo.curvyblocks.mixin;

import com.lirxowo.curvyblocks.physics.CurveBlockEffects;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class LevelMaterialMixin {
    @Inject(method = {
            "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z"
    }, at = @At("HEAD"))
    private void curvyblocks$protectWorld(CallbackInfoReturnable<Boolean> callback) {
        CurveBlockEffects.rejectWorldMutation();
    }
}
