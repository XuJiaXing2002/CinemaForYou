package com.cinemaforyou.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 为已有屏幕输入视频链接并播放（不创建新屏）。
 * 支持网页链接（B站/YouTube）、直链 http(s)、file: 本地路径。
 */
public class ScreenLinkInputScreen extends Screen {

    private final UUID screenId;
    private EditBox urlField;

    public ScreenLinkInputScreen(UUID screenId) {
        super(Component.literal("输入视频链接"));
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        Button title = Button.builder(
                Component.literal("§e输入视频链接 → 播放到当前屏幕"),
                btn -> {}
        ).bounds(cx - 155, cy - 60, 310, 16).build();
        addRenderableWidget(title);

        urlField = new EditBox(this.font, 300, 18,
                Component.translatable("gui.cinemaforyou.url_input.url"));
        urlField.setX(cx - 150);
        urlField.setY(cy - 36);
        urlField.setMaxLength(512);
        urlField.setHint(Component.literal("https://... 或 file:视频路径"));
        addRenderableWidget(urlField);
        setInitialFocus(urlField);

        addRenderableWidget(Button.builder(
                Component.literal("▶ 播放"),
                btn -> onPlay()
        ).bounds(cx - 150, cy - 8, 95, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("📂 本地视频…"),
                btn -> Minecraft.getInstance().gui.setScreen(new VideoLibraryScreen(screenId))
        ).bounds(cx - 50, cy - 8, 95, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("取消"),
                btn -> onClose()
        ).bounds(cx + 50, cy - 8, 95, 20).build());

        Button hint = Button.builder(
                Component.literal("§7支持：B站/YouTube 链接、视频直链、file:本地文件（可在设置里浏览）"),
                btn -> {}
        ).bounds(cx - 155, cy + 22, 310, 16).build();
        addRenderableWidget(hint);
    }

    private void onPlay() {
        String url = urlField.getValue().trim();
        if (url.isEmpty()) {
            urlField.setHint(Component.literal("§c请输入链接或本地路径！"));
            setInitialFocus(urlField);
            return;
        }
        ScreenSoundSettingsScreen.playOn(screenId, url);
        onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int key = event.key();
        if (key == 256) { // ESC
            onClose();
            return true;
        }
        if (key == 257 || key == 335) { // Enter
            onPlay();
            return true;
        }
        return super.keyPressed(event);
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
