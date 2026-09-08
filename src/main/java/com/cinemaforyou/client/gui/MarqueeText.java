package com.cinemaforyou.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 滚动显示完整文字的标签（marquee）。
 *
 * <p>当文字宽度超出控件宽度时不截断、不省略：先在起点停顿约 1.6 秒，
 * 然后以约 30px/s 向左滚动，直到尾部也完整露出（停留 1 秒），再回到开头循环。
 * 宽度放得下时与普通静态文本一样直接显示。
 *
 * <p>用法：叠放在行的主按钮上方（本控件不拦截鼠标事件，点击穿透到按钮）。
 *
 * <p>注意：26.2 的普通文字绘制不解析 {@code §} 旧式颜色码（含 § 的字符串
 * 渲染为空），因此构造时先把 {@code §x} 码解析成真正的 Style 组件再绘制。
 */
@Environment(EnvType.CLIENT)
public class MarqueeText extends AbstractWidget {

    /** 起点/终点停顿（毫秒）与滚动速度（像素/毫秒）。 */
    private static final long PAUSE_START_MS = 1600L;
    private static final long PAUSE_END_MS = 1000L;
    private static final double SPEED_PX_PER_MS = 0.030; // ≈30px/s
    private static final int TRAIL_GAP = 36;

    private final Component fullText;
    private final int color;
    /** 文字在控件内的垂直偏移（顶部对齐基准 y）。 */
    private final int textTop;

    public MarqueeText(int x, int y, int width, int height, String text, int color) {
        this(x, y, width, height, text, color, 6);
    }

    public MarqueeText(int x, int y, int width, int height, String text, int color, int textTop) {
        super(x, y, width, height, Component.literal(""));
        this.fullText = parseLegacy(text == null ? "" : text);
        this.color = color;
        this.textTop = textTop;
    }

    /** 把 §x 旧式颜色码解析为带 Style 的组件（普通文字绘制不支持 § 原样渲染）。 */
    private static Component parseLegacy(String s) {
        MutableComponent root = Component.literal("");
        StringBuilder seg = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00a7' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                i++;
                if (seg.length() > 0) {
                    root.append(Component.literal(seg.toString()).withStyle(style));
                    seg.setLength(0);
                }
                style = applyCode(style, code);
            } else {
                seg.append(c);
            }
        }
        if (seg.length() > 0) {
            root.append(Component.literal(seg.toString()).withStyle(style));
        }
        return root;
    }

    private static Style applyCode(Style base, char code) {
        if (code == 'r') return Style.EMPTY;
        if (code == 'l') return base.withBold(true);
        if (code == 'o') return base.withItalic(true);
        if (code == 'n') return base.withUnderlined(true);
        if (code == 'm') return base.withStrikethrough(true);
        int rgb = legacyColor(code);
        if (rgb >= 0) {
            return base.withColor(TextColor.fromRgb(rgb));
        }
        return base;
    }

    /** 旧式颜色码 → RGB（与 Minecraft 配色一致）。 */
    private static int legacyColor(char code) {
        return switch (code) {
            case '0' -> 0x000000;
            case '1' -> 0x0000AA;
            case '2' -> 0x00AA00;
            case '3' -> 0x00AAAA;
            case '4' -> 0xAA0000;
            case '5' -> 0xAA00AA;
            case '6' -> 0xFFAA00;
            case '7' -> 0xAAAAAA;
            case '8' -> 0x555555;
            case '9' -> 0x5555FF;
            case 'a' -> 0x55FF55;
            case 'b' -> 0x55FFFF;
            case 'c' -> 0xFF5555;
            case 'd' -> 0xFF55FF;
            case 'e' -> 0xFFFF55;
            case 'f' -> 0xFFFFFF;
            default -> -1;
        };
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor,
                                            int mouseX, int mouseY, float partialTick) {
        Font font = Minecraft.getInstance().font;
        int tw = font.width(fullText);
        int w = Math.max(1, this.getWidth());
        int drawX = this.getX();
        int drawY = this.getY() + textTop;
        if (tw <= w) {
            extractor.text(font, fullText, drawX, drawY, color);
            return;
        }
        int scrollDist = tw - w + TRAIL_GAP; // 让尾部完全露出后再留一段空
        long scrollMs = (long) (scrollDist / SPEED_PX_PER_MS);
        long cycleMs = PAUSE_START_MS + scrollMs + PAUSE_END_MS;
        long t = System.currentTimeMillis() % Math.max(1L, cycleMs);
        int offset;
        if (t < PAUSE_START_MS) {
            offset = 0;
        } else if (t < PAUSE_START_MS + scrollMs) {
            offset = (int) Math.round((t - PAUSE_START_MS) * SPEED_PX_PER_MS);
        } else {
            offset = scrollDist;
        }
        // 裁剪到控件范围，滚动时不越界盖住相邻按钮
        extractor.enableScissor(drawX, this.getY(), w, this.getHeight());
        extractor.text(font, fullText, drawX - offset, drawY, color);
        extractor.disableScissor();
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClicked) {
        return false; // 让下层按钮接收点击
    }

    @Override
    protected void updateWidgetNarration(
            net.minecraft.client.gui.narration.NarrationElementOutput narrationOutput) {
        this.defaultButtonNarrationText(narrationOutput);
    }
}
