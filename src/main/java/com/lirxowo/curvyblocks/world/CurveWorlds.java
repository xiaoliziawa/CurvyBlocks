package com.lirxowo.curvyblocks.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.level.Level;

public final class CurveWorlds {
    private static final Map<Level, CurveIndex> LEVELS = new ConcurrentHashMap<>();

    private CurveWorlds() {
    }

    public static CurveIndex get(Level level) {
        return LEVELS.get(level);
    }

    public static void register(Level level, CurveIndex curves) {
        LEVELS.put(level, curves);
    }

    public static void unload(Level level) {
        LEVELS.remove(level);
    }
}
