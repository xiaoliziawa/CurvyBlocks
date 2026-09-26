package com.lirxowo.curvyblocks.geometry;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class CurveGeometry {
    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 FORWARD = new Vec3(0.0, 0.0, 1.0);
    private static final double VERTICAL_THRESHOLD = 0.9;
    private static final double BEND_SAMPLING_FACTOR = 8.0;
    private static final double MAX_ARCH_SPREAD = 0.9;
    private static final double ARCH_SPREAD_FACTOR = 2.0;
    private static final int SEGMENT_GROUP_SHIFT = 4;
    private static final int SEGMENT_GROUP_SIZE = 1 << SEGMENT_GROUP_SHIFT;

    private final List<Sample> samples;
    private final List<AABB> segmentBounds;
    private final AABB[] groupBounds;
    private final AABB bounds;
    private final double length;
    private final int[] spanEnds;
    private VoxelShape[] segmentShapes;

    public CurveGeometry(List<CurvePoint> points, int thickness, CrossSection section) {
        this(points, thickness, section, List.of());
    }

    public CurveGeometry(List<CurvePoint> points, int thickness, CrossSection section, List<CurveBend> bends) {
        validate(points, thickness);
        if (!bends.isEmpty() && bends.size() != points.size() - 1) {
            throw new IllegalArgumentException("Curve bend count does not match spans");
        }
        spanEnds = new int[points.size() - 1];
        double radius = thickness / (CurveLimits.UNITS_PER_BLOCK * 2.0);
        List<Sample> sampleList = new ArrayList<>();
        Vec3[] tangents = tangents(points);
        double distance = 0.0;
        Vec3 previousRight = null;
        Vec3 previousPosition = null;
        for (int edge = 0; edge < points.size() - 1; edge++) {
            Vec3 start = points.get(edge).position();
            Vec3 end = points.get(edge + 1).position();
            Vec3 first = start.add(tangents[edge].scale(1.0 / 3.0));
            Vec3 second = end.subtract(tangents[edge + 1].scale(1.0 / 3.0));
            CurveBend bend = bends.isEmpty() ? CurveBend.NONE : bends.get(edge);
            boolean bent = !bend.isEmpty();
            double bendLength = bend.offset().length();
            Vec3 archSpread = Vec3.ZERO;
            if (bent) {
                Vec3 chord = end.subtract(start);
                double alignment = Math.clamp(Math.min(tangents[edge].dot(chord), tangents[edge + 1].dot(chord))
                        / chord.lengthSqr(), 0.0, 1.0);
                double spread = alignment * Math.min(MAX_ARCH_SPREAD, ARCH_SPREAD_FACTOR * bendLength / chord.length());
                archSpread = chord.scale(spread);
            }
            double controlLength = start.distanceTo(first) + first.distanceTo(second) + second.distanceTo(end)
                    + bendLength * BEND_SAMPLING_FACTOR;
            int steps = Math.max(2, (int) Math.ceil(controlLength * CurveLimits.SAMPLES_PER_BLOCK));
            if (sampleList.size() + steps + 1 > CurveLimits.MAX_SAMPLES) {
                throw new IllegalArgumentException("Too many curve samples");
            }
            for (int step = edge == 0 ? 0 : 1; step <= steps; step++) {
                double t = step / (double) steps;
                Vec3 position = CurveMath.bezier(start, first, second, end, t);
                Vec3 tangent = CurveMath.bezierTangent(start, first, second, end, t);
                if (bent) {
                    double weight = bend.weight(t);
                    double derivative = bend.derivative(t);
                    double axial = t - bend.center();
                    position = position.add(bend.offset().scale(weight)).add(archSpread.scale(weight * axial));
                    tangent = tangent.add(bend.offset().scale(derivative)).add(archSpread.scale(derivative * axial + weight));
                }
                if (tangent.lengthSqr() < CurveLimits.EPSILON) {
                    throw new IllegalArgumentException("Curve folds back on itself");
                }
                tangent = tangent.normalize();
                Vec3 right = previousRight == null ? perpendicular(tangent)
                        : previousRight.subtract(tangent.scale(previousRight.dot(tangent)));
                right = right.lengthSqr() < CurveLimits.EPSILON ? perpendicular(tangent) : right.normalize();
                Vec3 up = tangent.cross(right).normalize();
                if (previousPosition != null) {
                    distance += previousPosition.distanceTo(position);
                }
                sampleList.add(new Sample(position, tangent, right, up, distance, BlockPos.containing(position)));
                previousRight = right;
                previousPosition = position;
            }
            spanEnds[edge] = sampleList.size() - 1;
        }
        List<AABB> segmentList = new ArrayList<>(sampleList.size() - 1);
        AABB previousRing = ringBounds(sampleList.getFirst(), radius, section);
        AABB totalBounds = previousRing;
        for (int i = 1; i < sampleList.size(); i++) {
            AABB ring = ringBounds(sampleList.get(i), radius, section);
            AABB segment = previousRing.minmax(ring);
            segmentList.add(segment);
            totalBounds = totalBounds.minmax(segment);
            previousRing = ring;
        }
        samples = List.copyOf(sampleList);
        segmentBounds = List.copyOf(segmentList);
        groupBounds = groupBounds(segmentBounds);
        bounds = totalBounds;
        length = distance;
    }

    private static AABB[] groupBounds(List<AABB> segments) {
        AABB[] groups = new AABB[(segments.size() + SEGMENT_GROUP_SIZE - 1) >> SEGMENT_GROUP_SHIFT];
        for (int i = 0; i < segments.size(); i++) {
            int group = i >> SEGMENT_GROUP_SHIFT;
            groups[group] = groups[group] == null ? segments.get(i) : groups[group].minmax(segments.get(i));
        }
        return groups;
    }

    private static void validate(List<CurvePoint> points, int thickness) {
        if (points.size() < 2 || points.size() > CurveLimits.MAX_POINTS
                || thickness < CurveLimits.MIN_THICKNESS || thickness > CurveLimits.MAX_THICKNESS) {
            throw new IllegalArgumentException("Invalid curve size");
        }
        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            double distance = points.get(i - 1).position().distanceTo(points.get(i).position());
            if (distance < CurveLimits.MIN_POINT_DISTANCE) {
                throw new IllegalArgumentException("Curve points are too close");
            }
            total += distance;
        }
        if (total > CurveLimits.MAX_CONTROL_LENGTH) {
            throw new IllegalArgumentException("Curve is too long");
        }
    }

    private static Vec3[] tangents(List<CurvePoint> points) {
        Vec3[] result = new Vec3[points.size()];
        for (int i = 0; i < points.size(); i++) {
            CurvePoint point = points.get(i);
            if (i == 0 || i == points.size() - 1) {
                boolean start = i == 0;
                Vec3 offset = start ? points.get(1).position().subtract(point.position())
                        : point.position().subtract(points.get(i - 1).position());
                result[i] = point.normal().lengthSqr() < CurveLimits.EPSILON ? offset
                        : point.normal().scale(offset.length() * (start ? 1.0 : -1.0));
            } else {
                Vec3 before = points.get(i - 1).position();
                Vec3 after = points.get(i + 1).position();
                Vec3 direction = after.subtract(before);
                if (direction.lengthSqr() < CurveLimits.EPSILON) {
                    throw new IllegalArgumentException("Reversing curve points");
                }
                result[i] = direction.normalize().scale(Math.min(point.position().distanceTo(before),
                        point.position().distanceTo(after)));
            }
        }
        return result;
    }

    private static Vec3 perpendicular(Vec3 tangent) {
        return tangent.cross(Math.abs(tangent.y) > VERTICAL_THRESHOLD ? FORWARD : UP).normalize();
    }

    private static AABB ringBounds(Sample sample, double radius, CrossSection section) {
        double x = extent(sample, radius, section, 0);
        double y = extent(sample, radius, section, 1);
        double z = extent(sample, radius, section, 2);
        Vec3 point = sample.position();
        return new AABB(point.x - x, point.y - y, point.z - z, point.x + x, point.y + y, point.z + z);
    }

    private static double extent(Sample sample, double radius, CrossSection section, int axis) {
        if (section == CrossSection.SQUARE) {
            return radius * (Math.abs(CurveMath.coordinate(sample.right(), axis))
                    + Math.abs(CurveMath.coordinate(sample.up(), axis)));
        }
        double tangent = CurveMath.coordinate(sample.tangent(), axis);
        return radius * Math.sqrt(Math.max(0.0, 1.0 - tangent * tangent));
    }

    public List<Sample> samples() {
        return samples;
    }

    public boolean containsCenterline(Vec3 position, double tolerance) {
        double reach = tolerance + CurveLimits.EPSILON;
        AABB area = new AABB(position.x - reach, position.y - reach, position.z - reach,
                position.x + reach, position.y + reach, position.z + reach);
        if (!bounds.intersects(area)) {
            return false;
        }
        double maximum = tolerance * tolerance;
        for (int i = nextSegment(area, 0); i >= 0; i = nextSegment(area, i + 1)) {
            if (CurveMath.pointSegmentDistanceSquared(position, samples.get(i).position(), samples.get(i + 1).position()) <= maximum) {
                return true;
            }
        }
        return false;
    }

    public Sample project(Vec3 position) {
        int closest = 0;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (int group = 0; group < groupBounds.length; group++) {
            if (CurveMath.distanceToBoxSquared(groupBounds[group], position.x, position.y, position.z) >= closestDistance) {
                continue;
            }
            int end = Math.min(segmentBounds.size(), (group + 1) << SEGMENT_GROUP_SHIFT);
            for (int i = group << SEGMENT_GROUP_SHIFT; i < end; i++) {
                double distance = CurveMath.pointSegmentDistanceSquared(position,
                        samples.get(i).position(), samples.get(i + 1).position());
                if (distance < closestDistance) {
                    closest = i;
                    closestDistance = distance;
                }
            }
        }
        Sample start = samples.get(closest);
        Sample end = samples.get(closest + 1);
        return start.interpolate(end, CurveMath.segmentProjection(position, start.position(), end.position()));
    }

    public List<AABB> segmentBounds() {
        return segmentBounds;
    }

    public int nextSegment(AABB box, int from) {
        int count = segmentBounds.size();
        int index = from;
        while (index < count) {
            int group = index >> SEGMENT_GROUP_SHIFT;
            int groupEnd = Math.min(count, (group + 1) << SEGMENT_GROUP_SHIFT);
            if (groupBounds[group].intersects(box)) {
                for (; index < groupEnd; index++) {
                    if (segmentBounds.get(index).intersects(box)) {
                        return index;
                    }
                }
            }
            index = groupEnd;
        }
        return -1;
    }

    public VoxelShape segmentShape(int segment) {
        VoxelShape[] shapes = segmentShapes;
        if (shapes == null) {
            shapes = new VoxelShape[segmentBounds.size()];
            segmentShapes = shapes;
        }
        VoxelShape shape = shapes[segment];
        if (shape == null) {
            shape = Shapes.create(segmentBounds.get(segment));
            shapes[segment] = shape;
        }
        return shape;
    }

    public int controlSpan(int segment) {
        for (int span = 0; span < spanEnds.length; span++) {
            if (segment < spanEnds[span]) {
                return span;
            }
        }
        throw new IndexOutOfBoundsException(segment);
    }

    public int firstSegment(int span) {
        return span == 0 ? 0 : spanEnds[span - 1];
    }

    public int endSegment(int span) {
        return spanEnds[span];
    }

    public Sample sampleAt(int span, double fraction) {
        int first = firstSegment(span);
        int last = endSegment(span);
        double index = first + Math.clamp(fraction, 0.0, 1.0) * (last - first);
        int lower = Math.min((int) index, last);
        return lower == last ? samples.get(last) : samples.get(lower).interpolate(samples.get(lower + 1), index - lower);
    }

    public AABB bounds() {
        return bounds;
    }

    public double length() {
        return length;
    }

    public record Sample(Vec3 position, Vec3 tangent, Vec3 right, Vec3 up, double distance, BlockPos lightPosition) {
        public Vec3 offset(double horizontal, double vertical) {
            return position.add(right.scale(horizontal)).add(up.scale(vertical));
        }

        public Sample interpolate(Sample other, double fraction) {
            if (fraction <= 0.0) {
                return this;
            }
            if (fraction >= 1.0) {
                return other;
            }
            Vec3 point = position.lerp(other.position, fraction);
            Vec3 direction = tangent.lerp(other.tangent, fraction).normalize();
            Vec3 horizontal = right.lerp(other.right, fraction);
            horizontal = horizontal.subtract(direction.scale(horizontal.dot(direction)));
            horizontal = horizontal.lengthSqr() < CurveLimits.EPSILON ? perpendicular(direction) : horizontal.normalize();
            return new Sample(point, direction, horizontal, direction.cross(horizontal).normalize(),
                    distance + (other.distance - distance) * fraction, BlockPos.containing(point));
        }
    }
}
