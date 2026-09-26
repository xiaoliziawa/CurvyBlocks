package com.lirxowo.curvyblocks.client.render;

import java.util.ArrayList;
import java.util.List;

import com.lirxowo.curvyblocks.config.CurveClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class CurveHud {
    private static final int MARGIN = 8;
    private static final int PADDING = 4;
    private static final int BOTTOM_OFFSET = 58;
    private static final int MAX_WIDTH = 360;
    private static final int LINE_SPACING = 2;
    private static final int BACKGROUND_COLOR = 0x800D161C;
    private static final int TEXT_COLOR = 0xFFF0F2F3;
    private static final int FAINT_TEXT_COLOR = 0xA0F0F2F3;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private List<Component> previous;
    private int previousWidth;
    private int width;

    public void render(GuiGraphics graphics, List<Component> text, boolean faint) {
        if (text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        float scale = CurveClientConfig.HUD_SCALE.get().floatValue();
        int available = Math.clamp((int) ((graphics.guiWidth() - MARGIN * 2) / scale) - PADDING * 2, 1, MAX_WIDTH);
        if (text != previous || available != previousWidth) {
            lines.clear();
            width = 0;
            for (Component component : text) {
                for (FormattedCharSequence line : font.split(component, available)) {
                    lines.add(line);
                    width = Math.max(width, font.width(line));
                }
            }
            previous = text;
            previousWidth = available;
        }
        int lineHeight = font.lineHeight + LINE_SPACING;
        int boxHeight = lines.size() * lineHeight - LINE_SPACING + PADDING * 2;
        float top = Math.max(MARGIN, graphics.guiHeight() - BOTTOM_OFFSET - boxHeight * scale);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(MARGIN, top, 0.0F);
        pose.scale(scale, scale, 1.0F);
        if (!faint) {
            graphics.fill(0, 0, width + PADDING * 2, boxHeight, BACKGROUND_COLOR);
        }
        int color = faint ? FAINT_TEXT_COLOR : TEXT_COLOR;
        int y = PADDING;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, PADDING, y, color);
            y += lineHeight;
        }
        pose.popPose();
    }
}
