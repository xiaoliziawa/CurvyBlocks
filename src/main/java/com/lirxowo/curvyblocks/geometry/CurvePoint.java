package com.lirxowo.curvyblocks.geometry;

import net.minecraft.world.phys.Vec3;

public record CurvePoint(Vec3 position, Vec3 normal) {
    public CurvePoint {
        if (!CurveMath.isFinite(position) || !CurveMath.isFinite(normal)
                || Math.abs(position.x) > CurveLimits.MAX_COORDINATE
                || Math.abs(position.y) > CurveLimits.MAX_COORDINATE
                || Math.abs(position.z) > CurveLimits.MAX_COORDINATE) {
            throw new IllegalArgumentException("Invalid curve point");
        }
        normal = normal.lengthSqr() < CurveLimits.EPSILON ? Vec3.ZERO : normal.normalize();
    }
}
