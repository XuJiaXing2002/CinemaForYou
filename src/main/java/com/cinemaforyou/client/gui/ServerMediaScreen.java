package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.network.MediaLibraryClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务器媒体库：列出 服务器目录/cinema/videos/ 下的媒体文件。
 * 点 ▶ 播放到目标屏幕；＋队列 加入该屏播放队列；支持刷新。
 */
public class ServerMediaScreen extends Screen {

    private static final int ROWS_PER_PAGE = 7;

    private final UUID screenId;
    private int page = 0;
    private boolean loading = true;

    public ServerMediaScreen(UUID screenId) {
        super(Component.literal("服务器媒体库"));
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        MediaLibraryClient.setListener(files -> {
            loading = false;
            rebuildWidgets();
        });
        refresh();
    }

    @Override
    public void removed() {
        super.removed();
        MediaLibraryClient.clearListener();
    }

    private void refresh() {
        loading = true;
        page = 0;
        MediaLibraryClient.request();
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 30);
        int left = cx - w / 2;

        Button title = Button.builder(
                Component.literal("§e🖥 服务器媒体库（服务器 cinema/videos 目录）"), btn -> {}
        ).bounds(left, 12, w, 16).build();
        title.active = false;
        addRenderableWidget(title);

        List<String> files = MediaLibraryClient.cached();
        if (loading || files == null) {
            Button loadingBtn = Button.builder(
                    Component.literal("§7正在向服务器请求文件列表…（若一直无响应请检查服务端媒体服务）"),
                    btn -> refresh()
            ).bounds(left, 36, w, 20).build();
            loadingBtn.active = false;
            addRenderableWidget(loadingBtn);
        } else if (files.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§e服务器 cinema/videos 目录为空 - 请把视频文件放进服务器目录后再刷新"),
                    btn -> {}
            ).bounds(left, 36, w, 20).build();
            empty.active = false;
            addRenderableWidget(empty);
        } else {
            int maxPage = (files.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(files.size(), start + ROWS_PER_PAGE);
            int y = 36;
            for (int i = start; i < end; i++) {
                String name = files.get(i);
                String shortName = UiText.fit(name, 210);
                String url = MediaLibraryClient.sourceFor(name);
                addRenderableWidget(Button.builder(
                        Component.literal("§a▶ " + shortName),
                        btn -> {
                            ScreenSoundSettingsScreen.playOn(screenId, url);
                            onClose();
                        }
                ).bounds(left, y, 210, 20).build());
                addRenderableWidget(Button.builder(Component.literal("＋队列"),
                        btn -> {
                            com.cinemaforyou.CinemaForYouClient.clientConfig.addToQueue(
                                    screenId.toString(), url);
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                        "§a[CinemaForYou] 已加入队列: " + name
                                                + " §7（播完模式选「自动播放下一个」生效）"));
                            }
                        }
                ).bounds(left + 214, y, 66, 20).build());
                y += 22;
            }

            int py = this.height - 56;
            boolean hasPrev = page > 0;
            Button prev = Button.builder(Component.literal("§l◀"),
                    btn -> { page--; rebuildWidgets(); }
            ).bounds(left, py, 40, 20).build();
            prev.active = hasPrev;
            addRenderableWidget(prev);

            Button pageLabel = Button.builder(
                    Component.literal("§7" + (page + 1) + "/" + (maxPage + 1)), btn -> {}
            ).bounds(left + 45, py, 40, 20).build();
            pageLabel.active = false;
            addRenderableWidget(pageLabel);

            boolean hasNext = page < maxPage;
            Button next = Button.builder(Component.literal("§l▶"),
                    btn -> { page++; rebuildWidgets(); }
            ).bounds(left + 90, py, 40, 20).build();
            next.active = hasNext;
            addRenderableWidget(next);

            addRenderableWidget(Button.builder(Component.literal("🔄 刷新列表"),
                    btn -> refresh()
            ).bounds(left + 135, py, w - 135, 20).build());
        }

        addRenderableWidget(Button.builder(
                Component.literal("← 返回设置"),
                btn -> Minecraft.getInstance().gui.setScreen(
                        new ScreenSoundSettingsScreen(screenId))
        ).bounds(left, this.height - 30, 150, 20).build());
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
