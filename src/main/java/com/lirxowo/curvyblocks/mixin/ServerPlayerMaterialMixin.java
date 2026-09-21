package com.lirxowo.curvyblocks.mixin;

import com.lirxowo.curvyblocks.physics.CurvePhysics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMaterialMixin {
    @ModifyArg(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/particles/BlockParticleOption;<init>(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/level/block/state/BlockState;)V"), index = 1)
    private BlockState curvyblocks$extraLandingMaterial(BlockState original) {
        return CurvePhysics.supportState((Entity) (Object) this, original);
    }

    @WrapOperation(method = "doCheckFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 curvyblocks$extraLandingPosition(Vec3 position, double x, double y, double z, Operation<Vec3> original) {
        Entity entity = (Entity) (Object) this;
        return CurvePhysics.support(entity) == null ? original.call(position, x, y, z) : entity.position();
    }
}
