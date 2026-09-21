package com.lirxowo.curvyblocks.geometry;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CurveMath {
    private CurveMath() {
    }

    public static boolean isFinite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    public static Vec3 bezier(Vec3 start, Vec3 first, Vec3 second, Vec3 end, double t) {
        double inverse = 1.0 - t;
        return start.scale(inverse * inverse * inverse)
                .add(first.scale(3.0 * inverse * inverse * t))
                .add(second.scale(3.0 * inverse * t * t))
                .add(end.scale(t * t * t));
    }

    public static Vec3 bezierTangent(Vec3 start, Vec3 first, Vec3 second, Vec3 end, double t) {
        double inverse = 1.0 - t;
        return first.subtract(start).scale(3.0 * inverse * inverse)
                .add(second.subtract(first).scale(6.0 * inverse * t))
                .add(end.subtract(second).scale(3.0 * t * t));
    }

    public static double pointSegmentDistanceSquared(Vec3 point, Vec3 start, Vec3 end) {
        double fraction = segmentProjection(point, start, end);
        double x = start.x + (end.x - start.x) * fraction - point.x;
        double y = start.y + (end.y - start.y) * fraction - point.y;
        double z = start.z + (end.z - start.z) * fraction - point.z;
        return x * x + y * y + z * z;
    }

    public static double segmentProjection(Vec3 point, Vec3 start, Vec3 end) {
        double x = end.x - start.x;
        double y = end.y - start.y;
        double z = end.z - start.z;
        double lengthSquared = x * x + y * y + z * z;
        if (lengthSquared < CurveLimits.EPSILON) {
            return 0.0;
        }
        double projection = (point.x - start.x) * x + (point.y - start.y) * y + (point.z - start.z) * z;
        return Math.clamp(projection / lengthSquared, 0.0, 1.0);
    }

    public static double rayBox(Vec3 origin, Vec3 direction, AABB box, double maximum) {
        double near = 0.0;
        double far = maximum;
        for (int axis = 0; axis < 3; axis++) {
            double start = coordinate(origin, axis);
            double delta = coordinate(direction, axis);
            double min = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
            double max = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
            if (Math.abs(delta) < CurveLimits.EPSILON) {
                if (start < min || start > max) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            double first = (min - start) / delta;
            double second = (max - start) / delta;
            near = Math.max(near, Math.min(first, second));
            far = Math.min(far, Math.max(first, second));
            if (near > far) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return near;
    }

    public static double coordinate(Vec3 point, int axis) {
        return axis == 0 ? point.x : axis == 1 ? point.y : point.z;
    }

    public static Vec3 snap(Vec3 point, double step) {
        if (step <= 0.0) {
            return point;
        }
        return new Vec3(Math.rint(point.x / step) * step, Math.rint(point.y / step) * step,
                Math.rint(point.z / step) * step);
    }
}
