package com.lirxowo.curvyblocks.client.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class CurveHud {
    private static final int MARGIN = 8;
    private static final int PADDING = 5;
    private static final int BOTTOM_OFFSET = 58;
    private static final int MAX_WIDTH = 420;
    private static final int LINE_SPACING = 2;
    private static final int BACKGROUND_COLOR = 0xA00D161C;
    private static final int TEXT_COLOR = 0xFFF0F2F3;
    private final List<FormattedCharSequence> lines = new ArrayList<>();
    private List<Component> previous;
    private int previousWidth;
    private int width;

    public void render(GuiGraphics graphics, List<Component> text) {
        if (text.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int available = Math.max(1, Math.min(MAX_WIDTH, graphics.guiWidth() - (MARGIN + PADDING) * 2));
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
        int top = Math.max(MARGIN, graphics.guiHeight() - BOTTOM_OFFSET - lines.size() * lineHeight - PADDING * 2);
        graphics.fill(MARGIN, top, MARGIN + width + PADDING * 2, top + lines.size() * lineHeight + PADDING * 2, BACKGROUND_COLOR);
        int y = top + PADDING;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, MARGIN + PADDING, y, TEXT_COLOR);
            y += lineHeight;
        }
    }
}
