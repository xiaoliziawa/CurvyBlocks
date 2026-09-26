package com.lirxowo.curvyblocks.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

public final class CurveKeys {
    private static final String CATEGORY = "key.categories.curvyblocks";
    public static final KeyMapping TOGGLE = key("toggle", GLFW.GLFW_KEY_B);
    public static final KeyMapping FINISH = key("finish", GLFW.GLFW_KEY_ENTER);
    public static final KeyMapping CANCEL = key("cancel", GLFW.GLFW_KEY_X);
    public static final KeyMapping UNDO = key("undo", GLFW.GLFW_KEY_BACKSPACE);
    public static final KeyMapping GRID = key("grid", GLFW.GLFW_KEY_G);
    public static final KeyMapping THICKNESS = key("thickness", GLFW.GLFW_KEY_R);
    public static final KeyMapping SECTION = key("section", GLFW.GLFW_KEY_V);
    public static final KeyMapping AUTO_ROUTE = key("auto_route", GLFW.GLFW_KEY_LEFT_ALT);
    public static final KeyMapping HELP = key("help", GLFW.GLFW_KEY_H);

    private CurveKeys() {
    }

    private static KeyMapping key(String name, int code) {
        return new KeyMapping("key.curvyblocks." + name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(FINISH);
        event.register(CANCEL);
        event.register(UNDO);
        event.register(GRID);
        event.register(THICKNESS);
        event.register(SECTION);
        event.register(AUTO_ROUTE);
        event.register(HELP);
    }
}
