package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.network.PlayLogClient;
import com.cinemaforyou.client.video.VideoTitleResolver;
import com.cinemaforyou.network.PlayLogActionPayload;
import com.cinemaforyou.network.PlayLogPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 播放历史（服务器级）：显示谁在何时播放了什么；支持搜索、单条删除
 * （仅本人）、清空本人记录、立即播放/加入队列。
 */
public class HistoryScreen extends ScrollableSettingsScreen {

    private static final int PAGE_SIZE = 5;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UUID screenId;
    private String query = "";
    private int page = 0;
    private EditBox searchBox;

    public HistoryScreen(UUID screenId) {
        super(Component.literal("播放历史"));
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        PlayLogClient.setListener(() ->
                Minecraft.getInstance().execute(this::rebuildWidgets));
        VideoTitleResolver.setListener(() ->
                Minecraft.getInstance().execute(this::rebuildWidgets));
        ClientNetworkHandlers.requestPlayLog();
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
        int w = Math.min(330, this.width - 30);
        int left = cx - w / 2;
        int y = 8;

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                "§e播放历史（服务器，显示谁在何时播放）", GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 15;

        searchBox = new EditBox(this.font, left, ry(y), w - 62, 20,
                Component.literal("搜索片源/播放者"));
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.literal("🔍"),
                btn -> {
                    query = searchBox.getValue() == null ? "" : searchBox.getValue().trim();
                    page = 0;
                    rebuildWidgets();
                }
        ).bounds(left + w - 58, ry(y), 58, 20).build());
        y += 24;

        List<PlayLogPayload.Entry> all = new ArrayList<>(PlayLogClient.entries());
        all.sort((a, b) -> Long.compare(b.timeMs(), a.timeMs()));
        List<PlayLogPayload.Entry> filtered = filter(all);
        int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(filtered.size(), start + PAGE_SIZE);

        if (filtered.isEmpty()) {
            addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                    "§7暂无播放记录（播放过的视频与链接会出现在这里）",
                    GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 22;
        } else {
            for (int i = start; i < end; i++) {
                y = renderEntry(cx, left, w, y, filtered.get(i));
            }
        }

        // ── 底部固定操作区（不随内容滚动，分页样式与本地视频库一致） ──
        int bottom = this.height - 32 + scrollY;      // 固定到屏幕底部上方
        addRenderableWidget(Button.builder(Component.literal("🗑 清空我的历史"),
                btn -> ClientNetworkHandlers.sendPlayLogAction(
                        PlayLogActionPayload.ACTION_CLEAR_MINE, UUID.randomUUID())
        ).bounds(left, ry(bottom - 26), w, 20).build());

        final int tPages = totalPages;
        addPager(left, bottom, page, tPages,
                () -> page > 0, () -> page < tPages - 1,
                () -> { page = Math.max(0, page - 1); rebuildWidgets(); },
                () -> { page = Math.min(tPages - 1, page + 1); rebuildWidgets(); });
        addRenderableWidget(Button.builder(Component.literal("← 返回上一级"),
                btn -> onClose()).bounds(left + 140, bottom, Math.max(60, w - 140), 20).build());

        finishContent(this.height - 6);
    }

    private int renderEntry(int cx, int left, int w, int y, PlayLogPayload.Entry e) {
        String name = ClientConfig.displayNameFor(e.url());
        String time = FMT.format(Instant.ofEpochMilli(e.timeMs()).atZone(ZoneId.systemDefault()));
        boolean mine = isMine(e);
        // 请求补全标题（有备注/标题/直链时内部自动忽略）
        VideoTitleResolver.request(e.url());
        // 单行紧凑：▶ 名称（播放者 · 时间）＋ [＋队列] [✎备注] [✕删除]
        int qw = 40, nw = 36, dw = 44;
        int actionW = mine ? qw + nw + dw : qw + nw;
        String head = UiText.fit("§a▶ " + name + "  §7" + e.playerName() + " " + time,
                w - actionW - 8);
        addRenderableWidget(Button.builder(Component.literal(head),
                btn -> ScreenSoundSettingsScreen.playOn(screenId, e.url()))
                .bounds(left, ry(y), w - actionW - 4, 20).build());
        int x = left + w - actionW;
        addRenderableWidget(Button.builder(Component.literal("＋队列"),
                btn -> {
                    ClientConfig cfg = CinemaForYouClient.clientConfig;
                    if (cfg != null) {
                        cfg.addToQueue(screenId.toString(), e.url());
                        Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                "§7[CinemaForYou] 已加入该屏播放队列"));
                    }
                }
        ).bounds(x, ry(y), qw, 20).build());
        // 备注：给该视频链接起任意名字，显示时优先于标题/链接
        addRenderableWidget(Button.builder(Component.literal("✎备注"),
                btn -> {
                    ClientConfig cfg = CinemaForYouClient.clientConfig;
                    if (cfg == null) return;
                    String url = e.url();
                    openChild(new InputValueScreen(
                            "为该链接写备注（显示时优先于标题/链接）",
                            cfg.noteFor(url) == null ? "" : cfg.noteFor(url),
                            60,
                            v -> cfg.setNote(url, v)));
                }
        ).bounds(x + qw, ry(y), nw, 20).build());
        if (mine) {
            addRenderableWidget(Button.builder(Component.literal("✕"),
                    btn -> ClientNetworkHandlers.sendPlayLogAction(
                            PlayLogActionPayload.ACTION_DELETE, e.id()))
                    .bounds(x + qw + nw, ry(y), dw, 20).build());
        }
        y += 22;
        return y;
    }

    private boolean isMine(PlayLogPayload.Entry e) {
        try {
            net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
            return p != null && e.playerUuid() != null
                    && e.playerUuid().equals(p.getUUID().toString());
        } catch (Throwable t) {
            return false;
        }
    }

    private List<PlayLogPayload.Entry> filter(List<PlayLogPayload.Entry> entries) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return entries;
        List<PlayLogPayload.Entry> out = new ArrayList<>();
        for (PlayLogPayload.Entry e : entries) {
            if (ClientConfig.displayNameFor(e.url()).toLowerCase().contains(q)
                    || (e.playerName() != null && e.playerName().toLowerCase().contains(q))) {
                out.add(e);
            }
        }
        return out;
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
