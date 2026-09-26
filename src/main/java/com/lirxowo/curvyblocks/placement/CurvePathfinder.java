package com.lirxowo.curvyblocks.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import com.lirxowo.curvyblocks.config.CurveConfig;
import com.lirxowo.curvyblocks.geometry.CrossSection;
import com.lirxowo.curvyblocks.geometry.CurveBend;
import com.lirxowo.curvyblocks.geometry.CurveGeometry;
import com.lirxowo.curvyblocks.geometry.CurveLimits;
import com.lirxowo.curvyblocks.geometry.CurvePoint;
import com.lirxowo.curvyblocks.world.Curve;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class CurvePathfinder {
    private static final Vec3 UP = new Vec3(0.0, 1.0, 0.0);
    private static final Vec3 RIGHT = new Vec3(1.0, 0.0, 0.0);
    private static final double MIN_UP_PROJECTION_SQUARED = 0.01;
    private static final double SURFACE_CLEARANCE = 1.0 / 16.0;
    private static final double MIN_RISE = 1.0 / 16.0;
    private static final double HEIGHT_GROWTH = 1.5;
    private static final double CENTER_SEPARATION = 0.08;
    private static final double REFINEMENT_PRECISION = 1.0 / 64.0;
    private static final double DIAGONAL_PREFERENCE = 0.015;
    private static final double SIDE_PREFERENCE = 0.04;
    private static final double DOWN_PREFERENCE = 0.12;
    private static final double CENTER_PREFERENCE = 0.05;
    private static final int REFINEMENT_STEPS = 4;
    private static final int WORK_PER_TICK = 4;
    private static final long TIME_PER_TICK_NANOS = 2_000_000L;
    private final Curve source;
    private final CurveObstacles obstacles;
    private final Vec3 playerPosition;
    private final double placementRangeSquared;
    private final double maxLength;
    private final double maxRise;
    private final int maxCandidates;
    private final double clearanceRadius;
    private final List<CurveBend> bends;
    private final PriorityQueue<Trial> trials = new PriorityQueue<>(Comparator.comparingDouble(Trial::priority)
            .thenComparingLong(Trial::sequence));
    private Phase phase = Phase.ANALYZE;
    private State state = State.SEARCHING;
    private PlacementResult failure = PlacementResult.ROUTE_NOT_FOUND;
    private Curve working;
    private Curve result;
    private int activeSpan;
    private int evaluated;
    private int refinements;
    private long sequence;
    private double chordLength;
    private double lowerHeight;
    private double upperHeight;
    private Family refiningFamily;
    private Curve acceptedCurve;
    private CurveBend acceptedBend;

    public CurvePathfinder(Level level, Player player, Curve source) {
        this.source = source;
        working = source;
        obstacles = new CurveObstacles(level);
        playerPosition = player.getEyePosition();
        double range = CurveConfig.PLACEMENT_RANGE.get();
        placementRangeSquared = range * range;
        maxLength = CurveConfig.MAX_LENGTH.get();
        maxRise = Math.min(CurveConfig.AUTO_ROUTE_SEARCH_MARGIN.get(), CurveLimits.MAX_BEND_OFFSET)
                - CurveLimits.SURFACE_OFFSET;
        maxCandidates = CurveConfig.AUTO_ROUTE_MAX_CANDIDATES.get();
        clearanceRadius = source.diameter() * 0.5 * (source.section() == CrossSection.SQUARE ? Math.sqrt(2.0) : 1.0);
        bends = new ArrayList<>(Collections.nCopies(source.points().size() - 1, CurveBend.NONE));
        if (!source.bends().isEmpty()) {
            Collections.copy(bends, source.bends());
        }
    }

    public State state() {
        return state;
    }

    public PlacementResult failure() {
        return failure;
    }

    public Curve result() {
        return result;
    }

    public CurvePoint target() {
        return source.points().getLast();
    }

    public void advance() {
        long deadline = System.nanoTime() + TIME_PER_TICK_NANOS;
        for (int work = 0; work < WORK_PER_TICK && state == State.SEARCHING; work++) {
            switch (phase) {
                case ANALYZE -> analyze();
                case EXPLORE -> explore();
                case REFINE -> refine();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    private void analyze() {
        CurveGeometry geometry = working.geometry();
        int collision = obstacles.firstIntersection(geometry);
        if (collision < 0) {
            result = working;
            state = State.FOUND;
            return;
        }
        if (evaluated >= maxCandidates) {
            fail(PlacementResult.ROUTE_LIMIT);
            return;
        }
        activeSpan = geometry.controlSpan(collision);
        AABB blockers = null;
        int firstSegment = geometry.firstSegment(activeSpan);
        int endSegment = geometry.endSegment(activeSpan);
        int firstBlocked = -1;
        int lastBlocked = -1;
        List<AABB> segments = geometry.segmentBounds();
        for (int segment = firstSegment; segment < endSegment; segment++) {
            AABB blocker = obstacles.firstObstacle(segments.get(segment).deflate(CurveLimits.EPSILON));
            if (blocker != null) {
                blockers = blockers == null ? blocker : blockers.minmax(blocker);
                if (firstBlocked < 0) {
                    firstBlocked = segment;
                }
                lastBlocked = segment;
            }
        }
        if (blockers == null) {
            fail(PlacementResult.ROUTE_NOT_FOUND);
            return;
        }
        Vec3 start = source.points().get(activeSpan).position();
        Vec3 end = source.points().get(activeSpan + 1).position();
        Vec3 chord = end.subtract(start);
        chordLength = chord.length();
        Vec3 axis = chord.normalize();
        Vec3 up = UP.subtract(axis.scale(axis.y));
        if (up.lengthSqr() < MIN_UP_PROJECTION_SQUARED) {
            up = RIGHT.subtract(axis.scale(axis.x));
        }
        up = up.normalize();
        Vec3 side = axis.cross(up).normalize();
        double focus = Math.clamp(((firstBlocked + lastBlocked + 1) * 0.5 - firstSegment) / (endSegment - firstSegment),
                CurveLimits.MIN_BEND_CENTER, CurveLimits.MAX_BEND_CENTER);
        double[] centers = Math.abs(focus - CurveLimits.DEFAULT_BEND_CENTER) < CENTER_SEPARATION
                ? new double[]{CurveLimits.DEFAULT_BEND_CENTER}
                : new double[]{CurveLimits.DEFAULT_BEND_CENTER, focus};
        trials.clear();
        for (double center : centers) {
            addFamily(up, center, 0.0, blockers);
            addFamily(up.add(side).normalize(), center, DIAGONAL_PREFERENCE, blockers);
            addFamily(up.subtract(side).normalize(), center, DIAGONAL_PREFERENCE, blockers);
            addFamily(side, center, SIDE_PREFERENCE, blockers);
            addFamily(side.scale(-1.0), center, SIDE_PREFERENCE, blockers);
            addFamily(up.scale(-1.0), center, DOWN_PREFERENCE, blockers);
        }
        phase = Phase.EXPLORE;
    }

    private void addFamily(Vec3 direction, double center, double preference, AABB blockers) {
        center = (float) center;
        Vec3 base = source.geometry().sampleAt(activeSpan, center).position();
        double support = direction.x * (direction.x >= 0.0 ? blockers.maxX : blockers.minX)
                + direction.y * (direction.y >= 0.0 ? blockers.maxY : blockers.minY)
                + direction.z * (direction.z >= 0.0 ? blockers.maxZ : blockers.minZ);
        double height = Math.clamp(support - direction.dot(base) + clearanceRadius + SURFACE_CLEARANCE, MIN_RISE, maxRise);
        double bias = chordLength * (preference + Math.abs(center - CurveLimits.DEFAULT_BEND_CENTER) * CENTER_PREFERENCE);
        enqueue(new Family(direction, center, bias), height, 0.0);
    }

    private void enqueue(Family family, double height, double failedHeight) {
        double before = chordLength * family.center();
        double after = chordLength - before;
        double priority = Math.hypot(before, height) + Math.hypot(after, height) - chordLength + family.bias();
        trials.add(new Trial(family, height, failedHeight, priority, sequence++));
    }

    private void explore() {
        if (evaluated >= maxCandidates) {
            fail(PlacementResult.ROUTE_LIMIT);
            return;
        }
        Trial trial = trials.poll();
        if (trial == null) {
            fail(PlacementResult.ROUTE_NOT_FOUND);
            return;
        }
        CurveBend bend = createBend(trial.family(), trial.height());
        Curve candidate = evaluate(bend);
        if (candidate != null) {
            refiningFamily = trial.family();
            lowerHeight = trial.failedHeight();
            upperHeight = trial.height();
            acceptedCurve = candidate;
            acceptedBend = bend;
            refinements = 0;
            phase = Phase.REFINE;
        } else {
            double nextHeight = Math.min(maxRise, Math.max(trial.height() * HEIGHT_GROWTH, trial.height() + MIN_RISE));
            if (nextHeight > trial.height() + CurveLimits.EPSILON) {
                enqueue(trial.family(), nextHeight, trial.height());
            }
        }
    }

    private void refine() {
        if (evaluated >= maxCandidates || refinements >= REFINEMENT_STEPS || upperHeight - lowerHeight <= REFINEMENT_PRECISION) {
            bends.set(activeSpan, acceptedBend);
            working = acceptedCurve;
            acceptedCurve = null;
            acceptedBend = null;
            trials.clear();
            phase = Phase.ANALYZE;
            return;
        }
        double height = (lowerHeight + upperHeight) * 0.5;
        CurveBend bend = createBend(refiningFamily, height);
        Curve candidate = evaluate(bend);
        refinements++;
        if (candidate == null) {
            lowerHeight = height;
        } else {
            upperHeight = height;
            acceptedCurve = candidate;
            acceptedBend = bend;
        }
    }

    private CurveBend createBend(Family family, double height) {
        Vec3 direction = family.direction();
        return new CurveBend(new Vec3((float) (direction.x * height), (float) (direction.y * height),
                (float) (direction.z * height)), family.center());
    }

    private Curve evaluate(CurveBend bend) {
        evaluated++;
        List<CurveBend> candidateBends = new ArrayList<>(bends);
        candidateBends.set(activeSpan, bend);
        Curve candidate;
        try {
            candidate = new Curve(0L, source.material(), source.points(), source.thickness(), source.section(), candidateBends);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        if (candidate.geometry().length() > maxLength) {
            return null;
        }
        for (CurveGeometry.Sample sample : candidate.geometry().samples()) {
            if (sample.position().distanceToSqr(playerPosition) > placementRangeSquared) {
                return null;
            }
        }
        int collision = obstacles.firstIntersection(candidate.geometry());
        return collision < 0 || candidate.geometry().controlSpan(collision) > activeSpan ? candidate : null;
    }

    private void fail(PlacementResult reason) {
        failure = reason;
        state = State.FAILED;
    }

    public enum State {
        SEARCHING,
        FOUND,
        FAILED
    }

    private enum Phase {
        ANALYZE,
        EXPLORE,
        REFINE
    }

    private record Family(Vec3 direction, double center, double bias) {
    }

    private record Trial(Family family, double height, double failedHeight, double priority, long sequence) {
    }
}
