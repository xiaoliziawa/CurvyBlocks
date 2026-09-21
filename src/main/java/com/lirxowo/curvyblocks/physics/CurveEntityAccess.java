package com.lirxowo.curvyblocks.physics;

import net.minecraft.world.level.block.state.BlockState;

public interface CurveEntityAccess {
    CurveContactCache curvyblocks$contacts();

    void curvyblocks$onInsideBlock(BlockState state);
}
