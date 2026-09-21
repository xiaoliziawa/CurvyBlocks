package com.lirxowo.curvyblocks.mixin;

import com.lirxowo.curvyblocks.physics.CurvePhysics;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Boat.class)
public abstract class BoatMaterialMixin {
    @ModifyReturnValue(method = "getGroundFriction", at = @At("RETURN"))
    private float curvyblocks$groundFriction(float original) {
        return CurvePhysics.groundFriction((Entity) (Object) this, original);
    }
}
