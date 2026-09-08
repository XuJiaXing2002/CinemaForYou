package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.data.CinemaScreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕选择列表（Shift+按键 打开）：列出服务器上所有屏幕，
 * 点击某一项打开该屏幕的控制/设置。与玩家位置/朝向/距离无关。
 */
public class ScreenPickerScreen extends ScrollableSettingsScreen {

    public ScreenPickerScreen() {
        super(Component.literal("选择屏幕"));
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        buildContent();
    }

    @Override
    protected void buildContent() {
        int cx = this.width / 2;
        int w = Math.min(320, this.width - 30);
        int left = cx - w / 2;
        int y = 8;

        List<CinemaScreen> all = new ArrayList<>(ClientScreenManager.get().allScreens().values());
        all.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                "§e选择要打开设置的屏幕（Shift+按键 打开本页，与距离无关）",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 16;

        if (all.isEmpty()) {
            addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                    "§7当前没有可用的屏幕（先在游戏里创建屏幕）",
                    GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 22;
        } else {
            for (CinemaScreen screen : all) {
                String name = "§e" + screen.displayName()
                        + "§7 [" + screen.width() + "x" + screen.height() + "]";
                String src = screen.sourceUrl() == null || screen.sourceUrl().isEmpty()
                        ? "（空）"
                        : ClientConfig.displayNameFor(screen.sourceUrl());
                String fullLabel = name + "  §f" + src;
                addRenderableWidget(Button.builder(Component.literal(fullLabel),
                        btn -> openChild(new ScreenControlScreen(screen.id()))
                ).bounds(left, ry(y), w, 20).build());
                y += 24;
            }
            y += 4;
        }

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                "§7提示：不带 Shift 直接按按键 = 对着屏幕时直接打开该屏控制；"
                        + "对着空气时打开全局设置。按键可在 选项→控制 中修改。",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 16;

        addRenderableWidget(Button.builder(Component.literal("← 返回上一级"),
                btn -> onClose()
        ).bounds(cx - 50, ry(y), 100, 20).build());
        y += 26;

        finishContent(y);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.fill(0, 0, this.width, this.height, 0x90101014);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
