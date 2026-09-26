package com.lirxowo.curvyblocks.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class CurveClientConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue HUD_SCALE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        HUD_SCALE = builder.translation("curvyblocks.configuration.hudScale")
                .defineInRange("hudScale", 0.85, 0.5, 2.0);
        SPEC = builder.build();
    }
}
