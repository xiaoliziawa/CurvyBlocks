package com.lirxowo.curvyblocks.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class CurveConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue MAX_LENGTH;
    public static final ModConfigSpec.DoubleValue PLACEMENT_RANGE;
    public static final ModConfigSpec.DoubleValue MATERIAL_MULTIPLIER;
    public static final ModConfigSpec.IntValue MAX_CURVES_PER_CHUNK;
    public static final ModConfigSpec.BooleanValue ALLOW_BLOCK_OVERLAP;
    public static final ModConfigSpec.BooleanValue SOLID_CURVES;
    public static final ModConfigSpec.BooleanValue OWNER_ONLY_REMOVAL;
    public static final ModConfigSpec.DoubleValue AUTO_ROUTE_SEARCH_MARGIN;
    public static final ModConfigSpec.IntValue AUTO_ROUTE_MAX_CANDIDATES;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        MAX_LENGTH = builder.translation("curvyblocks.configuration.maxLength")
                .defineInRange("maxLength", 64.0, 1.0, 256.0);
        PLACEMENT_RANGE = builder.translation("curvyblocks.configuration.placementRange")
                .defineInRange("placementRange", 32.0, 4.0, 128.0);
        MATERIAL_MULTIPLIER = builder.translation("curvyblocks.configuration.materialMultiplier")
                .defineInRange("materialMultiplier", 1.0, 0.1, 64.0);
        MAX_CURVES_PER_CHUNK = builder.translation("curvyblocks.configuration.maxCurvesPerChunk")
                .defineInRange("maxCurvesPerChunk", 256, 1, 1024);
        ALLOW_BLOCK_OVERLAP = builder.translation("curvyblocks.configuration.allowBlockOverlap")
                .define("allowBlockOverlap", false);
        SOLID_CURVES = builder.translation("curvyblocks.configuration.solidCurves")
                .define("solidCurves", true);
        OWNER_ONLY_REMOVAL = builder.translation("curvyblocks.configuration.ownerOnlyRemoval")
                .define("ownerOnlyRemoval", false);
        AUTO_ROUTE_SEARCH_MARGIN = builder.translation("curvyblocks.configuration.autoRouteSearchMargin")
                .defineInRange("autoRouteSearchMargin", 4.0, 1.0, 16.0);
        AUTO_ROUTE_MAX_CANDIDATES = builder.translation("curvyblocks.configuration.autoRouteMaxCandidates")
                .defineInRange("autoRouteMaxCandidates", 96, 16, 512);
        SPEC = builder.build();
    }

    private CurveConfig() {
    }
}
