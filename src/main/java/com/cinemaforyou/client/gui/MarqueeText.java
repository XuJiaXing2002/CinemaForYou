package com.cinemaforyou.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 悬停才滚动完整文字的行控制层（不绘制任何文字，只切换下层按钮的 message）。
 *
 * <p>原理：26.2 按钮自带"超宽自动滚动"且无法关停；这里利用悬停状态做门控：
 * <ul>
 *   <li>鼠标不在行上：按钮 message = 刚好放得下的开头文字（静态，不滚动不省略）；</li>
 *   <li>鼠标移上来：按钮 message = 完整文字 → 由按钮原生通道滚动显示全部；</li>
 *   <li>移开后恢复开头文字并复位。</li>
 * </ul>
 * 文字渲染完全走按钮原生通道（已实测可靠），本控件不拦截点击。
 */
@Environment(EnvType.CLIENT)
public class MarqueeText extends AbstractWidget {

    private final Button target;
    private final Component fullText;
    private final Component headText;
    private boolean lastHovered = false;

    public MarqueeText(int x, int y, int width, int height, Button target, String text) {
        super(x, y, width, height, Component.literal(""));
        this.target = target;
        String raw = text == null ? "" : text;
        this.fullText = Component.literal(raw);
        this.headText = Component.literal(fitHead(raw, Math.max(8, width - 6)));
        this.lastHovered = false;
        target.setMessage(headText); // 初始即显示开头文字，避免首帧空白
    }

    /** 取恰好放得下的开头（按像素宽截断，不加省略号）。 */
    private static String fitHead(String s, int maxPx) {
        if (s.isEmpty()) return s;
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int end = s.length();
        while (end > 0 && font.width(s.substring(0, end)) > maxPx) {
            end--;
        }
        return s.substring(0, end);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor,
                                            int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHovered;
        if (hovered != lastHovered) {
            lastHovered = hovered;
            target.setMessage(hovered ? fullText : headText);
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
