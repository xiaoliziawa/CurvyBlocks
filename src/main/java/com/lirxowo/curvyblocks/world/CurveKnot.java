package com.lirxowo.curvyblocks.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record CurveKnot(long parentId, Vec3 position, double radius, AABB bounds, BlockPos lightPosition) {
    public CurveKnot(long parentId, Vec3 position, double radius) {
        this(parentId, position, radius,
                new AABB(position.x - radius, position.y - radius, position.z - radius,
                        position.x + radius, position.y + radius, position.z + radius), BlockPos.containing(position));
    }
}
