package com.lirxowo.curvyblocks.mixin;

import com.lirxowo.curvyblocks.physics.CurveContact;
import com.lirxowo.curvyblocks.physics.CurveContactCache;
import com.lirxowo.curvyblocks.physics.CurveEntityAccess;
import com.lirxowo.curvyblocks.physics.CurvePhysics;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMaterialMixin implements CurveEntityAccess {
    @Unique
    private CurveContactCache curvyblocks$contactCache;

    @Shadow
    protected abstract void onInsideBlock(BlockState state);

    @Override
    public CurveContactCache curvyblocks$contacts() {
        if (curvyblocks$contactCache == null) {
            curvyblocks$contactCache = new CurveContactCache();
        }
        return curvyblocks$contactCache;
    }

    @Override
    public void curvyblocks$onInsideBlock(BlockState state) {
        onInsideBlock(state);
    }

    @ModifyReturnValue(method = "getOnPos(F)Lnet/minecraft/core/BlockPos;", at = @At("RETURN"))
    private BlockPos curvyblocks$supportPosition(BlockPos original) {
        return CurvePhysics.supportPosition((Entity) (Object) this, original);
    }

    @ModifyReturnValue(method = {"getBlockStateOn", "getBlockStateOnLegacy"}, at = @At("RETURN"))
    private BlockState curvyblocks$supportState(BlockState original) {
        return CurvePhysics.supportState((Entity) (Object) this, original);
    }

    @ModifyReturnValue(method = "getInBlockState", at = @At("RETURN"))
    private BlockState curvyblocks$bodyState(BlockState original) {
        return CurvePhysics.bodyState((Entity) (Object) this, original);
    }

    @WrapOperation(method = {"getBlockSpeedFactor", "getBlockJumpFactor"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 0))
    private BlockState curvyblocks$bodyFactors(Level level, BlockPos position, Operation<BlockState> original) {
        return CurvePhysics.bodyState((Entity) (Object) this, original.call(level, position));
    }

    @WrapOperation(method = {"getBlockSpeedFactor", "getBlockJumpFactor"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            ordinal = 1))
    private BlockState curvyblocks$supportFactors(Level level, BlockPos position, Operation<BlockState> original) {
        return CurvePhysics.supportState((Entity) (Object) this, original.call(level, position));
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"))
    private boolean curvyblocks$stepSource(BlockState state, Operation<Boolean> original) {
        return original.call(CurvePhysics.supportState((Entity) (Object) this, state));
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;vibrationAndSoundEffectsFromBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;ZZLnet/minecraft/world/phys/Vec3;)Z"))
    private boolean curvyblocks$stepEffects(Entity entity, BlockPos position, BlockState state, boolean playSound,
                                           boolean broadcastEvent, Vec3 movement, Operation<Boolean> original) {
        CurveContact contact = CurvePhysics.support(entity);
        return contact == null ? original.call(entity, position, state, playSound, broadcastEvent, movement)
                : original.call(entity, contact.position(), contact.material(), playSound, broadcastEvent, movement);
    }

    @WrapOperation(method = "spawnSprintParticle", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState curvyblocks$sprintMaterial(Level level, BlockPos position, Operation<BlockState> original) {
        return CurvePhysics.supportState((Entity) (Object) this, original.call(level, position));
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V"))
    private void curvyblocks$stepOn(Block block, Level level, BlockPos position, BlockState state, Entity entity,
                                   Operation<Void> original) {
        if (!CurvePhysics.stepOn(entity)) {
            original.call(block, level, position, state, entity);
        }
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;updateEntityAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"))
    private void curvyblocks$landOn(Block block, BlockGetter level, Entity entity, Operation<Void> original) {
        if (!CurvePhysics.landOn(entity)) {
            original.call(block, level, entity);
        }
    }

    @WrapOperation(method = "checkFallDamage", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;F)V"))
    private void curvyblocks$fallOn(Block block, Level level, BlockState state, BlockPos position, Entity entity,
                                   float fallDistance, Operation<Void> original) {
        if (!CurvePhysics.fallOn(entity, fallDistance)) {
            original.call(block, level, state, position, entity, fallDistance);
        }
    }

    @Inject(method = "checkInsideBlocks", at = @At("TAIL"))
    private void curvyblocks$inside(CallbackInfo callback) {
        CurvePhysics.inside((Entity) (Object) this);
    }
}
