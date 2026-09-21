package com.lirxowo.curvyblocks.mixin.client;

import com.lirxowo.curvyblocks.client.CurvePicking;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftPickMixin {
    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void curvyblocks$pickMaterial(CallbackInfo callback) {
        if (CurvePicking.pickBlock((Minecraft) (Object) this)) {
            callback.cancel();
        }
    }
}
