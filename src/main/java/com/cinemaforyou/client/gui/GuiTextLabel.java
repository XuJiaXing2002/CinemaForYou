package com.cinemaforyou.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/**
 * 纯文本标签控件（无边框、无交互）：用于说明/标题/提示文字。
 *
 * <ul>
 *   <li>颜色默认黄色（用户要求不可点击文字高亮为黄）；</li>
 *   <li>对齐：LEFT = x 为左边缘；CENTER = x 为水平中心；RIGHT = x 为右边缘；</li>
 *   <li>居中的唯一基准是传入的中心线，不依赖其它控件。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class GuiTextLabel extends AbstractWidget {

    /** 黄色文字（与 §e 一致）。 */
    public static final int YELLOW = 0xFFFFE53A;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY_LIGHT = 0xFFB0B0B0;

    public enum Align { LEFT, CENTER, RIGHT }

    private final Component text;
    private final int color;
    private final Align align;

    /** x 语义随 align 变化（见类注释）。高度仅用于布局占位/可点区域，无边框。 */
    public GuiTextLabel(int x, int y, int width, int height,
                        String text, Align align, int color) {
        super(x, y, width, height, Component.literal(""));
        // 剥掉文本里残留的 § 颜色码（如 §7），保证整行统一按传入颜色（黄色）显示
        this.text = Component.literal(stripColorCodes(text));
        this.color = color;
        this.align = align;
    }

    private static String stripColorCodes(String s) {
        if (s == null || s.indexOf('§') < 0) return s == null ? "" : s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i += 2; // 跳过颜色码本身与所修饰的字符
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    public GuiTextLabel(int x, int y, int width, int height,
                        Component text, Align align, int color) {
        super(x, y, width, height, Component.literal(""));
        this.text = text;
        this.color = color;
        this.align = align;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor,
                                            int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int tw = font.width(text);
        int drawX = switch (align) {
            case LEFT -> this.getX();
            case CENTER -> this.getX() - tw / 2;
            case RIGHT -> this.getX() - tw;
        };
        // y 为文字顶部
        extractor.text(font, text, drawX, this.getY(), color);
    }

    @Override
    protected void updateWidgetNarration(
            net.minecraft.client.gui.narration.NarrationElementOutput narrationOutput) {
        this.defaultButtonNarrationText(narrationOutput);
    }
}
