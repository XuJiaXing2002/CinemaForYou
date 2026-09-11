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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 播放历史（服务器级）：显示谁在何时播放了什么；支持搜索、单条删除
 * （仅本人）、清空本人记录、立即播放/加入队列。
 *
 * <ul>
 *   <li>无参构造：总设置入口——主页按玩家分组汇总（一行一个玩家：玩家名 + 历史 N 条），
 *       点玩家行进入该玩家的历史明细页（明细页沿用现有条目渲染与全部按钮；
 *       播放/入队仍向所有屏幕发送申请，与全局入口一致）；</li>
 *   <li>{@link #HistoryScreen(UUID)}：屏幕控制页入口——只作用于该屏（保持原样）。</li>
 * </ul>
 *
 * <p>数据来源：服务端 PlayLog（{@link PlayLogPayload.Entry}，含 playerUuid/playerName），
 * 按 playerUuid 分组（无 uuid 时按名字归组），显示名取 playerName（缺失为"未知玩家"）。
 */
public class HistoryScreen extends ScrollableSettingsScreen {

    private static final int PAGE_SIZE = 5;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 目标屏幕；null = 全局入口（总设置入口的主页 / 玩家明细页）。 */
    private final UUID screenId;
    /** 全局入口玩家分组键（playerUuid，无 uuid 时为 "name:玩家名"）；null = 未按玩家过滤。 */
    private final String playerKey;
    /** 分组玩家显示名（明细页标题用）。 */
    private final String playerLabel;

    private String query = "";
    private int page = 0;
    private EditBox searchBox;
    /** 二次确认状态（与本地视频库一致的机制）：null=无；CLEAR_KEY=清空全部；否则为待删除记录 id。 */
    private static final String CLEAR_KEY = "__clear_mine__";
    private String pendingDelete = null;
    /** 全局播放入口的二次确认：待确认播放的 URL（首次点击只提示，再点一次真正播放）。 */
    private String pendingPlayUrl = null;

    /** 总设置入口：主页按玩家分组；播放/入队作用于所有屏幕。 */
    public HistoryScreen() {
        this(null, null, null);
    }

    /** @param screenId 屏幕控制页入口（只作用于该屏）；null = 总设置入口（所有屏幕）。 */
    public HistoryScreen(UUID screenId) {
        this(screenId, null, null);
    }

    /** 全局主页点某玩家 → 该玩家的历史明细页（播放/入队仍作用于所有屏幕，逻辑不变）。 */
    public static HistoryScreen forPlayer(String playerKey, String playerLabel) {
        return new HistoryScreen(null, playerKey, playerLabel);
    }

    private HistoryScreen(UUID screenId, String playerKey, String playerLabel) {
        super(Component.literal("播放历史"));
        this.screenId = screenId;
        this.playerKey = playerKey;
        this.playerLabel = playerLabel;
    }

    /** 是否全局入口的玩家分组主页（未指定屏幕、也未过滤玩家）。 */
    private boolean isGroupHome() {
        return screenId == null && playerKey == null;
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
        // 内容起始 y=2：标题贴屏幕顶部，去掉原来的顶部留白（行距保持不变）
        int y = 2;

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                headerText(), GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
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

        if (isGroupHome()) {
            // ── 全局主页：按玩家分组，一行一个玩家（玩家名 + 历史 N 条），点玩家行进入其明细 ──
            List<PlayerGroup> groups = groupByPlayer(filtered);
            int totalPages = Math.max(1, (groups.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page >= totalPages) page = totalPages - 1;
            int start = page * PAGE_SIZE;
            int end = Math.min(groups.size(), start + PAGE_SIZE);

            if (groups.isEmpty()) {
                addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                        "§7暂无播放记录（播放过的视频与链接会出现在这里）",
                        GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
                y += 22;
            } else {
                for (int i = start; i < end; i++) {
                    PlayerGroup g = groups.get(i);
                    String label = UiText.fit(
                            "§b" + g.label() + "  §7历史 " + g.entries().size() + " 条", w - 10);
                    addRenderableWidget(Button.builder(Component.literal(label),
                            btn -> openChild(HistoryScreen.forPlayer(g.key(), g.label())))
                            .bounds(left, ry(y), w, 20).build());
                    y += 22;
                }
                y += 2;
            }
            buildFooter(left, w, totalPages);
            finishContent(this.height - 6);
            return;
        }

        // ── 明细页：全局（可带玩家过滤）或该屏（screenId != null）──
        if (playerKey != null) {
            List<PlayLogPayload.Entry> mine = new ArrayList<>();
            for (PlayLogPayload.Entry e : filtered) {
                if (playerKeyOf(e).equals(playerKey)) mine.add(e);
            }
            filtered = mine;
        }
        int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(filtered.size(), start + PAGE_SIZE);

        if (filtered.isEmpty()) {
            addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                    playerKey != null
                            ? "§7该玩家暂无播放记录" : "§7暂无播放记录（播放过的视频与链接会出现在这里）",
                    GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 22;
        } else {
            for (int i = start; i < end; i++) {
                y = renderEntry(cx, left, w, y, filtered.get(i));
            }
        }

        buildFooter(left, w, totalPages);
        finishContent(this.height - 6);
    }

    /** 该屏队列是否可改动：控制页入口且非本人屏幕（且非 OP≥2）时为 false（＋队列置灰）。 */
    private boolean canManageQueue() {
        return screenId == null || ScreenControlScreen.canManageScreen(screenId);
    }

    /** 标题文案：该屏 / 玩家明细 / 玩家分组主页（仅展示，行为不变）。 */
    private String headerText() {
        if (screenId != null) {
            return canManageQueue()
                    ? "§e播放历史（服务器，显示谁在何时播放）"
                    : "§e播放历史（服务器）· 非本人屏幕：仅可播放，＋队列已置灰";
        }
        if (playerKey != null) {
            return "§e播放历史：" + playerLabel + " → 向所有屏幕发送播放申请";
        }
        return "§e播放历史（服务器，按玩家分组）→ 向所有屏幕发送播放申请";
    }

    /**
     * 底部固定操作区（不随内容滚动，分页样式与本地视频库一致）：
     * 清空全部（两次点击确认） + 分页 + 返回上一级。
     */
    private void buildFooter(int left, int w, int totalPages) {
        int bottom = this.height - 32 + scrollY;      // 固定到屏幕底部上方
        addRenderableWidget(Button.builder(
                Component.literal(CLEAR_KEY.equals(pendingDelete)
                        ? "§c⚠确认清空全部?" : "🗑 清空全部"),
                btn -> {
                    // 清空记录：两次点击确认（与本地视频库删除同一套机制）
                    if (!CLEAR_KEY.equals(pendingDelete)) {
                        pendingDelete = CLEAR_KEY;
                        Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                "§c[CinemaForYou] 将清空你的全部播放历史（不可恢复），再点一次确认"));
                        rebuildWidgets();
                        return;
                    }
                    pendingDelete = null;
                    ClientNetworkHandlers.sendPlayLogAction(
                            PlayLogActionPayload.ACTION_CLEAR_MINE, UUID.randomUUID());
                }
        ).bounds(left, ry(bottom - 26), w, 20).build());

        final int tPages = totalPages;
        addPager(left, bottom, page, tPages,
                () -> page > 0, () -> page < tPages - 1,
                () -> { page = Math.max(0, page - 1); rebuildWidgets(); },
                () -> { page = Math.min(tPages - 1, page + 1); rebuildWidgets(); });
        addRenderableWidget(Button.builder(Component.literal("← 返回上一级"),
                btn -> onClose()).bounds(left + 140, bottom, Math.max(60, w - 140), 20).build());
    }

    /** 玩家分组：key = 玩家键，label = 显示名，entries = 该玩家的历史条目。 */
    private record PlayerGroup(String key, String label, List<PlayLogPayload.Entry> entries) {}

    /** 按玩家分组（搜索过滤后的历史），组按玩家名排序。 */
    private static List<PlayerGroup> groupByPlayer(List<PlayLogPayload.Entry> entries) {
        Map<String, List<PlayLogPayload.Entry>> map = new LinkedHashMap<>();
        for (PlayLogPayload.Entry e : entries) {
            map.computeIfAbsent(playerKeyOf(e), k -> new ArrayList<>()).add(e);
        }
        List<PlayerGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<PlayLogPayload.Entry>> e : map.entrySet()) {
            out.add(new PlayerGroup(e.getKey(), playerLabelOf(e.getValue().get(0)), e.getValue()));
        }
        out.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        return out;
    }

    /** 玩家分组键：优先 playerUuid；无 uuid 时按名字归组；都没有则 ""（未知玩家）。 */
    private static String playerKeyOf(PlayLogPayload.Entry e) {
        String uuid = e.playerUuid();
        if (uuid != null && !uuid.isEmpty()) return uuid;
        String name = e.playerName();
        return (name == null || name.isEmpty()) ? "" : "name:" + name;
    }

    /** 玩家显示名（缺失为"未知玩家"）。 */
    private static String playerLabelOf(PlayLogPayload.Entry e) {
        String name = e.playerName();
        return (name == null || name.isEmpty()) ? "未知玩家" : name;
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
        int headW = w - actionW - 4;
        boolean pending = e.url().equals(pendingPlayUrl);
        String headText = (pending ? "§c⚠ 再点一次: 向所有屏幕发送播放申请 ▶ " : "§a▶ ")
                + name + "  §7" + e.playerName() + " " + time;
        // 主按钮（点击播放）+ 控制层：整行超宽时悬停才由按钮原生滚动显示全部
        Button headBtn = Button.builder(Component.literal(""),
                btn -> onPlay(e.url()))
                .bounds(left, ry(y), headW, 20).build();
        addRenderableWidget(headBtn);
        addRenderableWidget(new MarqueeText(left, ry(y), headW, 20, headBtn, headText));
        int x = left + w - actionW;
        Button addQueueBtn = Button.builder(Component.literal("＋队列"),
                btn -> addToQueue(e.url())
        ).bounds(x, ry(y), qw, 20).build();
        addQueueBtn.active = canManageQueue();   // 非本人屏幕且非 OP：不可改动该屏队列（播放不受限）
        addRenderableWidget(addQueueBtn);
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
            String entryId = e.id().toString();
            addRenderableWidget(Button.builder(
                    Component.literal(entryId.equals(pendingDelete) ? "§c⚠确认?" : "✕删除"),
                    btn -> {
                        // 单条删除：两次点击确认（与本地视频库删除同一套机制）
                        if (!entryId.equals(pendingDelete)) {
                            pendingDelete = entryId;
                            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                    "§c[CinemaForYou] 将删除这条播放历史（不可恢复），再点一次确认"));
                            rebuildWidgets();
                            return;
                        }
                        pendingDelete = null;
                        ClientNetworkHandlers.sendPlayLogAction(
                                PlayLogActionPayload.ACTION_DELETE, e.id());
                        rebuildWidgets();
                    })
                    .bounds(x + qw + nw, ry(y), dw, 20).build());
        }
        y += 22;
        return y;
    }

    /** 播放：总设置入口先二次确认再向所有屏幕发播放申请；控制页入口直接播到该屏。 */
    private void onPlay(String url) {
        if (screenId == null) {
            if (!url.equals(pendingPlayUrl)) {
                pendingPlayUrl = url;
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                        "§e[CinemaForYou] 将向所有屏幕发送播放申请，再点一次确认"));
                rebuildWidgets();
                return;
            }
            if (ScreenSoundSettingsScreen.playOnAll(url)) {
                pendingPlayUrl = null;
            }
        } else {
            ScreenSoundSettingsScreen.playOn(screenId, url);
        }
    }

    /** 加入播放队列（服务端记录玩家名/时间）：总设置入口加入全局播放队列（不自动连播）。 */
    private void addToQueue(String url) {
        if (screenId == null) {
            ClientNetworkHandlers.sendGlobalQueueAdd(url);
        } else {
            ClientNetworkHandlers.sendQueueAdd(screenId, url);
        }
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
