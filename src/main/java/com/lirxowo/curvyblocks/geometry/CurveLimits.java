package com.lirxowo.curvyblocks.geometry;

public final class CurveLimits {
    public static final int MAX_POINTS = 32;
    public static final int MIN_THICKNESS = 2;
    public static final int MAX_THICKNESS = 16;
    public static final double UNITS_PER_BLOCK = 16.0;
    public static final double MIN_POINT_DISTANCE = 1.0 / UNITS_PER_BLOCK;
    public static final double MAX_COORDINATE = 30_000_000.0;
    public static final double MAX_CONTROL_LENGTH = 512.0;
    public static final double SAMPLES_PER_BLOCK = 12.0;
    public static final int MAX_SAMPLES = 8192;
    public static final double EPSILON = 1.0E-7;
    public static final double SURFACE_OFFSET = 1.0 / 1024.0;
    public static final double PICK_PADDING = 0.04;
    public static final double MAX_BEND_OFFSET = 16.0;
    public static final double MIN_BEND_CENTER = 0.25;
    public static final double MAX_BEND_CENTER = 0.75;
    public static final double DEFAULT_BEND_CENTER = 0.5;

    private CurveLimits() {
    }
}
