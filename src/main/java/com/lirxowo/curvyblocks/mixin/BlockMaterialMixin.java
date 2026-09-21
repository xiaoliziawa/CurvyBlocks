package com.lirxowo.curvyblocks.mixin;

import com.lirxowo.curvyblocks.physics.CurveBlockEffects;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public abstract class BlockMaterialMixin {
    @Inject(method = "pushEntitiesUp", at = @At("HEAD"))
    private static void curvyblocks$protectEntities(CallbackInfoReturnable<BlockState> callback) {
        CurveBlockEffects.rejectWorldMutation();
    }

    @Inject(method = {"dropResources", "popResource", "popResourceFromFace"}, at = @At("HEAD"))
    private static void curvyblocks$protectDrops(CallbackInfo callback) {
        CurveBlockEffects.rejectWorldMutation();
    }

    @Inject(method = "popExperience", at = @At("HEAD"))
    private void curvyblocks$protectExperience(CallbackInfo callback) {
        CurveBlockEffects.rejectWorldMutation();
    }
}
