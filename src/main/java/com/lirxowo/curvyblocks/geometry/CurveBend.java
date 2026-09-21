package com.lirxowo.curvyblocks.geometry;

import java.util.List;

import net.minecraft.world.phys.Vec3;

public record CurveBend(Vec3 offset, double center) {
    public static final CurveBend NONE = new CurveBend(Vec3.ZERO, CurveLimits.DEFAULT_BEND_CENTER);

    public CurveBend {
        if (!CurveMath.isFinite(offset) || offset.lengthSqr() > CurveLimits.MAX_BEND_OFFSET * CurveLimits.MAX_BEND_OFFSET
                || !Double.isFinite(center) || center < CurveLimits.MIN_BEND_CENTER || center > CurveLimits.MAX_BEND_CENTER) {
            throw new IllegalArgumentException("Invalid curve bend");
        }
    }

    public boolean isEmpty() {
        return offset.equals(Vec3.ZERO);
    }

    public double weight(double t) {
        double local = t <= center ? t / center : (1.0 - t) / (1.0 - center);
        double factor = local * (2.0 - local);
        return factor * factor;
    }

    public double derivative(double t) {
        double length = t <= center ? center : 1.0 - center;
        double local = t <= center ? t / length : (1.0 - t) / length;
        double derivative = 4.0 * local * (2.0 - local) * (1.0 - local) / length;
        return t <= center ? derivative : -derivative;
    }

    public static List<CurveBend> compact(List<CurveBend> bends) {
        for (CurveBend bend : bends) {
            if (!bend.isEmpty()) {
                return List.copyOf(bends);
            }
        }
        return List.of();
    }
}
