package com.cinemaforyou.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 可滚动的设置页基类：内容在虚拟画布上布局，超出视口的部分用滚轮滑动，
 * 右侧绘制滚动条轨道与滑块。
 *
 * <p>子类约定：
 * <ul>
 *   <li>在 {@link #buildContent()} 中按"虚拟 Y 坐标"布局控件（从 0 开始累加），
 *       布局完成后调用 {@link #finishContent(int)} 告知内容底部；</li>
 *   <li>{@link #ry(int)} 把虚拟坐标换算为当前屏幕坐标（已减去 scrollY）；</li>
 *   <li>滚动时基类自动 {@code clearWidgets()+buildContent()} 重建。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public abstract class ScrollableSettingsScreen extends Screen {

    /** 每次滚轮滚动像素数。 */
    private static final int SCROLL_STEP = 22;
    /** 滚动条宽度（px）。 */
    private static final int BAR_W = 5;

    protected int scrollY = 0;
    protected int contentBottom = 0;
    private boolean barDragging = false;

    protected ScrollableSettingsScreen(Component title) {
        super(title);
    }

    /** 子类在此创建全部控件（虚拟坐标），并调用 finishContent。 */
    protected abstract void buildContent();

    /**
     * 添加控件时跳过"完全位于视口外"的控件。
     *
     * <p>26.2 的 GuiRenderer 会对每个控件开 scissor 裁剪，完全在屏幕外的控件
     * 裁剪区宽/高为 0，直接抛 {@code Scissor size must be >0} 崩溃
     * （滚轮滚动设置页后必现）。部分可见的控件保留（裁剪区仍为正）。
     */
    @Override
    protected <T extends net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> T addRenderableWidget(T widget) {
        if (widget instanceof net.minecraft.client.gui.components.AbstractWidget aw) {
            int x0 = aw.getX();
            int y0 = aw.getY();
            int x1 = x0 + aw.getWidth();
            int y1 = y0 + aw.getHeight();
            if (x1 <= 0 || x0 >= this.width || y1 <= 0 || y0 >= this.height) {
                return widget; // 完全不可见：不加入渲染/事件列表
            }
        }
        return super.addRenderableWidget(widget);
    }

    /** 虚拟 Y → 当前屏幕 Y。 */
    protected int ry(int virtualY) {
        return virtualY - scrollY;
    }

    /** 布局完成：记录内容总高并夹紧滚动位置。 */
    protected void finishContent(int bottomVirtualY) {
        this.contentBottom = Math.max(0, bottomVirtualY);
        clampScroll();
    }

    protected int maxScroll() {
        return Math.max(0, contentBottom - (this.height - 6));
    }

    private void clampScroll() {
        int max = maxScroll();
        if (scrollY > max) scrollY = max;
        if (scrollY < 0) scrollY = 0;
    }

    protected void scrollBy(int delta) {
        int before = scrollY;
        scrollY += delta;
        clampScroll();
        if (scrollY != before) {
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        if (maxScroll() <= 0) return false;
        // verticalAmount > 0 = 向上滚 → 内容上移（scrollY 减小）
        scrollBy(verticalAmount > 0 ? -SCROLL_STEP : SCROLL_STEP);
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (key == 264) { // Down
            scrollBy(SCROLL_STEP);
            return true;
        }
        if (key == 265) { // Up
            scrollBy(-SCROLL_STEP);
            return true;
        }
        return super.keyPressed(event);
    }

    /** 绘制侧边滚动条（在控件之上）。 */
    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        int max = maxScroll();
        if (max <= 0) return;
        int trackX = this.width - BAR_W - 1;
        int trackY0 = 4;
        int trackY1 = this.height - 4;
        int trackH = trackY1 - trackY0;
        int thumbH = thumbHeight();
        int thumbY = thumbY();
        extractor.fill(trackX, trackY0, trackX + BAR_W, trackY1, 0x40FFFFFF);
        extractor.fill(trackX + 1, thumbY, trackX + BAR_W - 1, thumbY + thumbH, 0xB0FFFFFF);
    }

    private int trackX() {
        return this.width - BAR_W - 1;
    }

    private int trackY0() {
        return 4;
    }

    private int trackH() {
        return this.height - 8;
    }

    private int thumbHeight() {
        int trackH = trackH();
        return Math.max(14, (int) (trackH * (double) Math.min(trackH, this.height) / Math.max(trackH, contentBottom)));
    }

    private int thumbY() {
        int max = Math.max(1, maxScroll());
        return trackY0() + (int) ((trackH() - thumbHeight()) * (double) scrollY / max);
    }

    /** 把滚动位置设为与鼠标 Y 对应的比例（点击/拖动滚动条用）。 */
    private void scrollToMouseY(double mouseY) {
        int max = maxScroll();
        if (max <= 0) return;
        int trackH = trackH();
        int thumbH = thumbHeight();
        double frac = (mouseY - trackY0() - thumbH / 2.0) / Math.max(1, trackH - thumbH);
        int target = (int) Math.round(frac * max);
        if (target < 0) target = 0;
        if (target > max) target = max;
        if (target != scrollY) {
            scrollY = target;
            rebuildWidgets();
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClicked) {
        if (maxScroll() > 0 && event.button() == 0
                && event.x() >= trackX() && event.x() <= trackX() + BAR_W
                && event.y() >= trackY0() && event.y() <= trackY0() + trackH()) {
            barDragging = true;
            scrollToMouseY(event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClicked);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double deltaX, double deltaY) {
        if (barDragging) {
            scrollToMouseY(event.y());
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (barDragging) {
            barDragging = false;
            return true;
        }
        return super.mouseReleased(event);
    }
}
