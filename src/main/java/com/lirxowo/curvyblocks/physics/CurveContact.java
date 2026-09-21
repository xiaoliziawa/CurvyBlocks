package com.lirxowo.curvyblocks.physics;

import com.lirxowo.curvyblocks.world.Curve;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public record CurveContact(Curve curve, AABB bounds, BlockPos position) {
    public BlockState material() {
        return curve.material();
    }
}
