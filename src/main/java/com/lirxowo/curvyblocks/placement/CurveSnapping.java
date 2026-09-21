package com.lirxowo.curvyblocks.placement;

import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.lirxowo.curvyblocks.world.Curve;
import com.lirxowo.curvyblocks.world.CurveIndex;
import com.lirxowo.curvyblocks.world.CurveKnot;

import net.minecraft.world.phys.Vec3;

public final class CurveSnapping {
    private static final double ENDPOINT_SNAP_DISTANCE = 0.4;
    private static final double ENDPOINT_LENGTH_FRACTION = 0.25;

    private CurveSnapping() {
    }

    public static CurvePoint onHit(CurveIndex.Hit hit) {
        CurveKnot knot = hit.knot();
        if (knot == null) {
            return onCurve(hit.curve(), hit.position());
        }
        Vec3 normal = hit.position().subtract(knot.position());
        if (normal.lengthSqr() < CurveLimits.EPSILON) {
            normal = hit.curve().geometry().project(knot.position()).right();
        }
        return new CurvePoint(knot.position(), normal, hit.curve().id());
    }

    public static CurvePoint onCurve(Curve curve, Vec3 hitPosition) {
        CurveGeometry geometry = curve.geometry();
        CurveGeometry.Sample sample = geometry.project(hitPosition);
        double endpointDistance = Math.min(ENDPOINT_SNAP_DISTANCE, geometry.length() * ENDPOINT_LENGTH_FRACTION);
        if (sample.distance() <= endpointDistance) {
            CurveGeometry.Sample first = geometry.samples().getFirst();
            return new CurvePoint(first.position(), first.tangent().scale(-1.0), curve.id());
        }
        if (geometry.length() - sample.distance() <= endpointDistance) {
            CurveGeometry.Sample last = geometry.samples().getLast();
            return new CurvePoint(last.position(), last.tangent(), curve.id());
        }
        Vec3 offset = hitPosition.subtract(sample.position());
        Vec3 normal;
        if (curve.section() == CrossSection.SQUARE) {
            double horizontal = offset.dot(sample.right());
            double vertical = offset.dot(sample.up());
            normal = Math.abs(horizontal) > Math.abs(vertical)
                    ? sample.right().scale(Math.copySign(1.0, horizontal))
                    : sample.up().scale(Math.copySign(1.0, vertical));
        } else {
            normal = offset.subtract(sample.tangent().scale(offset.dot(sample.tangent())));
            normal = normal.lengthSqr() < CurveLimits.EPSILON ? sample.right() : normal.normalize();
        }
        return new CurvePoint(sample.position(), normal, curve.id());
    }
}
