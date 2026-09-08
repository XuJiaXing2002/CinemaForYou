package com.cinemaforyou.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 悬停才滚动的完整文字标签（marquee）。
 *
 * <p>文字超宽时：鼠标不在这条上 = 保持静止（显示开头部分，裁剪不越界）；
 * 鼠标移上来 = 以较快速度（约 70px/s）从暂停 0.8s 后开始向左滚动，
 * 尾部完全露出后停 0.8s 回到开头循环。宽度放得下时始终静态显示。
 *
 * <p>绘制走 26.2 的文字收集通道（textRendererForWidget → collector.accept，
 * 自带按控件矩形的裁剪），叠放在行按钮上方；本控件不拦截鼠标点击。
 */
@Environment(EnvType.CLIENT)
public class MarqueeText extends AbstractWidget {

    private static final long PAUSE_START_MS = 800L;
    private static final long PAUSE_END_MS = 800L;
    private static final double SPEED_PX_PER_MS = 0.070; // ≈70px/s
    private static final int TRAIL_GAP = 40;
    /** 文字垂直位置：控件顶部 + 该偏移（近似按钮内文字垂直居中）。 */
    private static final int TEXT_TOP = 6;

    private final Component fullText;
    /** 记录悬停期间滚动进程，离开后清零（回来从头滚）。 */
    private long scrollClockMs = 0L;
    private boolean wasHovered = false;

    public MarqueeText(int x, int y, int width, int height, String text) {
        super(x, y, width, height, Component.literal(""));
        this.fullText = parseLegacy(text == null ? "" : text);
    }

    /** 把 §x 旧式颜色码解析为带 Style 的组件（文字通道不渲染裸 §）。 */
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
        int rgb = switch (code) {
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
        return rgb < 0 ? base : base.withColor(TextColor.fromRgb(rgb));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor,
                                            int mouseX, int mouseY, float partialTick) {
        ActiveTextCollector collector = extractor.textRendererForWidget(
                this, GuiGraphicsExtractor.HoveredTextEffects.NONE);
        int tw = Minecraft.getInstance().font.width(fullText);
        int w = Math.max(1, this.getWidth());
        int x = this.getX();
        int yTop = this.getY() + TEXT_TOP;
        // 裁剪到本控件矩形：静止时不越界，滚动时也只在本行内显示
        ActiveTextCollector.Parameters params = collector.defaultParameters()
                .withScissor(this.getX(), this.getY(), w, this.getHeight());

        int offset = 0;
        if (tw > w) {
            if (isHovered) {
                if (!wasHovered) {
                    wasHovered = true;
                    scrollClockMs = 0L;
                }
                int scrollDist = tw - w + TRAIL_GAP;
                long scrollMs = (long) (scrollDist / SPEED_PX_PER_MS);
                long cycleMs = PAUSE_START_MS + scrollMs + PAUSE_END_MS;
                long t = scrollClockMs % Math.max(1L, cycleMs);
                if (t >= PAUSE_START_MS) {
                    long moved = t - PAUSE_START_MS;
                    if (moved < scrollMs) {
                        offset = (int) Math.round(moved * SPEED_PX_PER_MS);
                    } else {
                        offset = scrollDist;
                    }
                }
                scrollClockMs += 16L; // ≈60fps 步进
            } else {
                wasHovered = false;
                scrollClockMs = 0L;
            }
        } else {
            wasHovered = false;
            scrollClockMs = 0L;
        }
        collector.accept(TextAlignment.LEFT, x - offset, yTop, params, fullText);
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
