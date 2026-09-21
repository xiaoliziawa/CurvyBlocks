package com.lirxowo.curvyblocks.mixin;

import java.util.List;

import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.lirxowo.curvyblocks.world.CurveWorlds;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityGetter.class)
public interface EntityGetterMixin {
    @Inject(method = "getEntityCollisions", at = @At("RETURN"), cancellable = true)
    private void curvyblocks$addCollisions(Entity entity, AABB box, CallbackInfoReturnable<List<VoxelShape>> callback) {
        if ((Object) this instanceof Level level) {
            CurveIndex index = CurveWorlds.get(level);
            if (index != null && !index.isEmpty() && CurveConfig.SOLID_CURVES.get()) {
                List<VoxelShape> original = callback.getReturnValue();
                List<VoxelShape> result = index.collisions(box, original);
                if (result != original) {
                    callback.setReturnValue(result);
                }
            }
        }
    }
}
