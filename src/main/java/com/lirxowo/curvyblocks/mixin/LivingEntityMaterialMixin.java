package com.lirxowo.curvyblocks.mixin;

import com.lirxowo.curvyblocks.physics.CurveContact;
import com.lirxowo.curvyblocks.physics.CurvePhysics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMaterialMixin {
    @WrapOperation(method = "checkFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
    private boolean curvyblocks$landingSource(BlockState state, Operation<Boolean> original) {
        return original.call(CurvePhysics.supportState((Entity) (Object) this, state));
    }

    @WrapOperation(method = "checkFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;addLandingEffects(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/LivingEntity;I)Z"))
    private boolean curvyblocks$landingEffects(BlockState state, ServerLevel level, BlockPos position,
                                              BlockState landedState, LivingEntity entity, int count, Operation<Boolean> original) {
        CurveContact contact = CurvePhysics.support(entity);
        return contact == null ? original.call(state, level, position, landedState, entity, count)
                : original.call(contact.material(), level, contact.position(), contact.material(), entity, count);
    }

    @ModifyArg(method = "checkFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/particles/BlockParticleOption;<init>(Lnet/minecraft/core/particles/ParticleType;Lnet/minecraft/world/level/block/state/BlockState;)V"), index = 1)
    private BlockState curvyblocks$landingParticleMaterial(BlockState original) {
        return CurvePhysics.supportState((Entity) (Object) this, original);
    }

    @WrapOperation(method = "playBlockFallSound", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState curvyblocks$fallSoundMaterial(Level level, BlockPos position, Operation<BlockState> original) {
        return CurvePhysics.supportState((Entity) (Object) this, original.call(level, position));
    }

    @WrapOperation(method = "playBlockFallSound", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/block/SoundType;"))
    private SoundType curvyblocks$fallSoundPosition(BlockState state, LevelReader level, BlockPos position,
                                                   Entity entity, Operation<SoundType> original) {
        return original.call(state, level, CurvePhysics.supportPosition(entity, position), entity);
    }
}
