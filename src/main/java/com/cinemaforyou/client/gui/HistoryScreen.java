package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * 播放历史记录：本机所有屏幕播放过的视频/链接（最新在前）。
 * 支持：立即播放到目标屏幕、加入队列、单条删除、清空全部。
 */
public class HistoryScreen extends Screen {

    private static final int ROWS_PER_PAGE = 6;

    private final UUID screenId;
    private int page = 0;

    public HistoryScreen(UUID screenId) {
        super(Component.literal("播放历史"));
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        int cx = this.width / 2;
        int w = Math.min(340, this.width - 24);
        int left = cx - w / 2;
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        List<ClientConfig.HistoryItem> history =
                (cfg != null && cfg.history != null) ? cfg.history : List.of();

        Button title = Button.builder(
                Component.literal("§e🕘 播放历史（最近 " + history.size() + " 条）"),
                btn -> {}
        ).bounds(left, 12, w, 16).build();
        title.active = false;
        addRenderableWidget(title);

        if (history.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§7暂无历史记录 - 播放过的视频/链接会出现在这里"),
                    btn -> {}
            ).bounds(left, 36, w, 20).build();
            empty.active = false;
            addRenderableWidget(empty);
        } else {
            int maxPage = (history.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(history.size(), start + ROWS_PER_PAGE);
            int y = 36;
            int playW = w - 64 - 26 - 6; // 播放区 + ＋队列(64) + ✕(26) + 间隔
            for (int i = start; i < end; i++) {
                ClientConfig.HistoryItem item = history.get(i);
                String label = (item.name == null || item.name.isEmpty())
                        ? ClientConfig.displayNameFor(item.url) : item.name;
                String shortName = UiText.fit(label, playW - 20);
                String url = item.url;
                addRenderableWidget(Button.builder(
                        Component.literal("§a▶ " + shortName),
                        btn -> {
                            ScreenSoundSettingsScreen.playOn(screenId, url);
                            onClose();
                        }
                ).bounds(left, y, playW, 20).build());
                addRenderableWidget(Button.builder(
                        Component.literal("＋队列"),
                        btn -> {
                            ClientConfig c = CinemaForYouClient.clientConfig;
                            if (c != null) c.addToQueue(screenId.toString(), url);
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                        "§a[CinemaForYou] 已加入队列: " + shortName));
                            }
                        }
                ).bounds(left + playW + 2, y, 64, 20).build());
                addRenderableWidget(Button.builder(
                        Component.literal("✕"),
                        btn -> {
                            ClientConfig c = CinemaForYouClient.clientConfig;
                            if (c != null) c.removeHistory(url);
                            rebuildWidgets();
                        }
                ).bounds(left + playW + 68, y, 26, 20).build());
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

            Button clearAll = Button.builder(
                    Component.literal("🗑 清空全部历史"),
                    btn -> {
                        ClientConfig c = CinemaForYouClient.clientConfig;
                        if (c != null) {
                            c.clearHistory();
                        }
                        page = 0;
                        rebuildWidgets();
                    }
            ).bounds(left + 135, py, w - 135, 20).build();
            addRenderableWidget(clearAll);
        }

        addRenderableWidget(Button.builder(
                Component.literal("← 返回设置"),
                btn -> Minecraft.getInstance().gui.setScreen(
                        new CinemaSettingsScreen())
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
