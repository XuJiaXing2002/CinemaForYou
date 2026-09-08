package com.cinemaforyou.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * 滚动显示完整文字的标签（marquee）。
 *
 * <p>当文字宽度超出控件宽度时不截断、不省略：先在起点停顿约 1.6 秒，
 * 然后以约 30px/s 向左滚动，直到尾部也完整露出（停留 1 秒），再回到开头循环。
 * 宽度放得下时与普通静态文本一样直接显示。
 *
 * <p>用法：叠放在行的主按钮上方（本控件不拦截鼠标事件，点击穿透到按钮）。
 * 文字用 String 重载绘制（与按钮文字同一渲染路径）。
 */
@Environment(EnvType.CLIENT)
public class MarqueeText extends AbstractWidget {

    /** 起点/终点停顿（毫秒）与滚动速度（像素/毫秒）。 */
    private static final long PAUSE_START_MS = 1600L;
    private static final long PAUSE_END_MS = 1000L;
    private static final double SPEED_PX_PER_MS = 0.030; // ≈30px/s
    private static final int TRAIL_GAP = 36;

    private final String fullText;
    private final int color;
    /** 文字在控件内的垂直偏移（顶部对齐基准 y）。 */
    private final int textTop;
    /** 诊断：首帧渲染日志只打一次，避免刷屏。 */
    private boolean debugLogged = false;

    public MarqueeText(int x, int y, int width, int height, String text, int color) {
        this(x, y, width, height, text, color, 6);
    }

    public MarqueeText(int x, int y, int width, int height, String text, int color, int textTop) {
        super(x, y, width, height, net.minecraft.network.chat.Component.literal(""));
        this.fullText = text == null ? "" : text;
        this.color = color;
        this.textTop = textTop;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor,
                                            int mouseX, int mouseY, float partialTick) {
        try {
            // TEMP-DEBUG: 2px 红条标记本控件是否被渲染（诊断后删除）
            extractor.fill(this.getX(), this.getY(), this.getX() + 2, this.getY() + this.getHeight(), 0xFFFF0000);
            if (!debugLogged) {
                debugLogged = true;
                org.slf4j.LoggerFactory.getLogger("CinemaForYou/Marquee")
                        .info("[Marquee] first render: x={} y={} w={} h={} text='{}'",
                                this.getX(), this.getY(), this.getWidth(), this.getHeight(),
                                fullText.length() > 40 ? fullText.substring(0, 40) : fullText);
            }
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
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("CinemaForYou/Marquee")
                    .error("[Marquee] render error: " + t, t);
        }
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
