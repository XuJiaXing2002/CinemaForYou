package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.video.VideoTitleResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 播放队列管理：查看队列顺序，支持上移/下移/删除/清空。
 * 队列决定"播完行为=自动播放下一个"时的顺序。
 */
public class ScreenQueueManagerScreen extends Screen {
    @Override
    public void onClose() {
        if (!GuiNav.back(this)) {
            super.onClose();
        }
    }


    private static final int ROWS_PER_PAGE = 7;

    private final UUID screenId;
    private int page = 0;

    public ScreenQueueManagerScreen(UUID screenId) {
        super(Component.literal("播放队列"));
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        // 标题补全后刷新列表（备注 > 标题 > 链接 的显示规则见 ClientConfig.displayNameFor）
        VideoTitleResolver.setListener(() ->
                Minecraft.getInstance().execute(this::rebuildWidgets));
        int cx = this.width / 2;
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        List<String> queue = (cfg != null)
                ? new ArrayList<>(cfg.queueFor(screenId.toString())) : new ArrayList<>();
        int w = Math.min(310, this.width - 30);
        int left = cx - w / 2;

        Button title = Button.builder(
                Component.literal("§e📋 播放队列（" + queue.size() + " 首）— 播完=自动播放下一个时按此顺序循环"),
                btn -> {}
        ).bounds(left, 12, w, 16).build();
        addRenderableWidget(title);

        if (queue.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§7队列为空 - 在「📂 本地视频」或「🕘 历史」里点 ＋队列 添加"),
                    btn -> {}
            ).bounds(left, 36, w, 20).build();
            addRenderableWidget(empty);
        } else {
            int maxPage = (queue.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(queue.size(), start + ROWS_PER_PAGE);
            int y = 36;
            for (int i = start; i < end; i++) {
                String url = queue.get(i);
                VideoTitleResolver.request(url);
                int nameW = w - 3 * 30 - 6;
                String fullName = (i + 1) + ". " + ClientConfig.displayNameFor(url);
                Button nameBtn = Button.builder(Component.literal(""), btn -> {})
                        .bounds(left, y, nameW, 20).build();
                addRenderableWidget(nameBtn);
                // 序号+名称（含标题/备注）：悬停才滚动显示全部
                addRenderableWidget(new MarqueeText(left, y, nameW, 20, nameBtn, fullName));

                int bx = left + (w - 3 * 30 - 6) + 2;
                int fi = i;
                addRenderableWidget(Button.builder(Component.literal("▲"),
                        btn -> move(fi, -1)
                ).bounds(bx, y, 28, 20).build());
                addRenderableWidget(Button.builder(Component.literal("▼"),
                        btn -> move(fi, 1)
                ).bounds(bx + 30, y, 28, 20).build());
                addRenderableWidget(Button.builder(Component.literal("✕"),
                        btn -> remove(fi)
                ).bounds(bx + 60, y, 28, 20).build());
                y += 22;
            }

            // 底部固定：清空队列(全宽)在上，分页 + 返回在底行
            Button clear = Button.builder(Component.literal("🗑 清空队列"),
                    btn -> {
                        if (CinemaForYouClient.clientConfig != null) {
                            CinemaForYouClient.clientConfig.clearQueue(screenId.toString());
                        }
                        page = 0;
                        rebuildWidgets();
                    }
            ).bounds(left, this.height - 56, w, 20).build();
            addRenderableWidget(clear);

            int py = this.height - 30;
            boolean hasPrev = page > 0;
            Button prev = Button.builder(Component.literal("§l◀"),
                    btn -> { page--; rebuildWidgets(); }
            ).bounds(left, py, 40, 20).build();
            prev.active = hasPrev;
            addRenderableWidget(prev);

            Button pageLabel = Button.builder(
                    Component.literal("§7" + (page + 1) + "/" + (maxPage + 1)), btn -> {}
            ).bounds(left + 45, py, 44, 20).build();
            pageLabel.active = false;
            addRenderableWidget(pageLabel);

            boolean hasNext = page < maxPage;
            Button next = Button.builder(Component.literal("§l▶"),
                    btn -> { page++; rebuildWidgets(); }
            ).bounds(left + 94, py, 40, 20).build();
            next.active = hasNext;
            addRenderableWidget(next);
        }

        addRenderableWidget(Button.builder(
                Component.literal("← 返回上一级"),
                btn -> onClose()
        ).bounds(left + 140, this.height - 30, Math.max(60, w - 140), 20).build());
    }

    private List<String> liveQueue() {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        return (cfg != null)
                ? new ArrayList<>(cfg.queueFor(screenId.toString())) : new ArrayList<>();
    }

    private void saveQueue(List<String> q) {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg == null) return;
        cfg.screenPlaylist.put(screenId.toString(), q);
        cfg.save();
    }

    private void move(int index, int delta) {
        List<String> q = liveQueue();
        if (index < 0 || index >= q.size()) return;
        int target = index + delta;
        if (target < 0 || target >= q.size()) return;
        String item = q.remove(index);
        q.add(target, item);
        saveQueue(q);
        if (target < page * ROWS_PER_PAGE) page--;
        if (target >= (page + 1) * ROWS_PER_PAGE) page++;
        rebuildWidgets();
    }

    private void remove(int index) {
        List<String> q = liveQueue();
        if (index < 0 || index >= q.size()) return;
        q.remove(index);
        saveQueue(q);
        int maxPage = Math.max(0, (q.size() - 1) / ROWS_PER_PAGE);
        page = Math.min(page, maxPage);
        rebuildWidgets();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
