package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.network.QueueClient;
import com.cinemaforyou.client.video.VideoTitleResolver;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.network.QueueEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 播放队列管理（服务端队列的客户端只读镜像 + 操作入口）。
 *
 * <p>两种范围（同一界面类，传入不同范围即可）：
 * <ul>
 *   <li>无参构造：总设置的「全局播放队列管理」入口——上下两个区块：
 *       <b>区块 A</b> 为全局播放队列（原样保留：条目<b>不参与任何屏幕的自动连播</b>，
 *       管理员手动点击某条才向所有屏幕的 owner 发送播放申请，两次点击确认）；
 *       <b>区块 B</b> 为「各屏幕队列（汇总）」——按客户端已知屏幕分组只读展示每屏队列
 *       （空队列也显示组头），点条目同样走"向所有屏幕发播放申请"流程（<b>不</b>直接在该屏播放），
 *       并保留删除/清空/上移下移（作用于该屏队列，删除/清空沿用两次点击确认）；</li>
 *   <li>{@link #ScreenQueueManagerScreen(UUID)}：屏幕控制页入口——只展示该屏队列，
 *       点条目 = 从该屏队列立即播放，顺序仍决定"播完行为=自动播放下一个"的次序（原行为不变）。</li>
 * </ul>
 *
 * <p>删除/清空/上移下移都发到服务端执行（服务端为唯一数据源：屏幕队列 owner 或 OP≥2 可操作，
 * 全局队列仅 OP≥2），删除与清空沿用两次点击确认。
 */
public class ScreenQueueManagerScreen extends Screen {
    @Override
    public void onClose() {
        if (!GuiNav.back(this)) {
            super.onClose();
        }
    }

    private static final int ROWS_PER_PAGE = 8;
    /** 队列条目"加入时间"显示格式（与播放历史一致）。 */
    private static final DateTimeFormatter QUEUE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 目标屏幕；null = 总设置入口（区块 A 全局播放队列 + 区块 B 各屏幕队列汇总）。 */
    private final UUID screenId;
    private int page = 0;
    /** 二次确认状态（与本地视频库一致的机制）：待删除条目键（"屏幕UUID#下标" / "global#下标"）。 */
    private String pendingRemoveKey = null;
    /** 二次确认状态：待清空队列的屏幕 UUID 字符串。 */
    private String pendingClearScreenId = null;
    /** 二次确认状态：全局播放队列待清空。 */
    private boolean pendingClearGlobal = false;
    /** 区块 A 二次确认：待确认"向所有屏幕发播放申请"的全局条目 URL。 */
    private String pendingGlobalPlayUrl = null;
    /** 区块 B 二次确认：待确认"向所有屏幕发播放申请"的屏幕条目键（"屏幕UUID#下标"）。 */
    private String pendingApplyKey = null;

    /** 列表行类型：区块 A 全局条目/空提示、区块 B 标题/组头/条目、无屏幕提示。 */
    private enum RowKind { GLOBAL_ENTRY, GLOBAL_EMPTY, SECTION_TITLE, SCREEN_HEADER, SCREEN_ENTRY, NO_SCREEN }

    /** 列表行：组头行（screen != null）或条目行（entryScreen + entryIndex；全局条目 entryScreen 为 null）。 */
    private record Row(RowKind kind, CinemaScreen screen, UUID entryScreen, int entryIndex) {
        /** 区块 A 全局播放队列条目。 */
        static Row globalEntry(int index) {
            return new Row(RowKind.GLOBAL_ENTRY, null, null, index);
        }

        /** 某屏队列条目。 */
        static Row screenEntry(UUID screenId, int index) {
            return new Row(RowKind.SCREEN_ENTRY, null, screenId, index);
        }

        /** 某屏组头（列表从属该屏的条目均排在组头之后）。 */
        static Row screenHeader(CinemaScreen s) {
            return new Row(RowKind.SCREEN_HEADER, s, null, -1);
        }

        /** 无附加数据的行（区块标题/提示行）。 */
        static Row of(RowKind kind) {
            return new Row(kind, null, null, -1);
        }
    }

    /** 总设置入口（区块 A 全局播放队列 + 区块 B 各屏幕队列汇总）。 */
    public ScreenQueueManagerScreen() {
        this(null);
    }

    /**
     * @param screenId 只管理该屏队列（点条目=该屏播放）；null = 总设置入口
     *                 （区块 A 点条目=向所有屏幕发播放申请；区块 B 始终走申请流程）
     */
    public ScreenQueueManagerScreen(UUID screenId) {
        super(Component.literal(screenId == null ? "全局播放队列管理" : "播放队列"));
        this.screenId = screenId;
    }

    /** 是否全局播放队列视图（总设置入口）。 */
    private boolean isGlobalView() {
        return screenId == null;
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        // 服务端队列广播到达后刷新；标题补全后也刷新列表
        QueueClient.setListener(() -> Minecraft.getInstance().execute(this::rebuildWidgets));
        VideoTitleResolver.setListener(() ->
                Minecraft.getInstance().execute(this::rebuildWidgets));

        int cx = this.width / 2;
        int w = Math.min(320, this.width - 30);
        int left = cx - w / 2;
        // 标题计数（与原来一致）：总设置入口只计区块 A 的全局条目；单屏视图计该屏条目
        int entries = isGlobalView() ? QueueClient.globalCount() : QueueClient.countFor(screenId);
        List<Row> rows = buildRows();

        addRenderableWidget(Button.builder(
                Component.literal(isGlobalView()
                        ? "§e📋 全局播放队列（共 " + entries
                                + " 首）— 点条目向所有屏幕发播放申请（不参与自动连播）"
                        : "§e📋 播放队列（" + entries + " 首）— 播完=自动播放下一个时按此顺序循环"),
                btn -> {}
        ).bounds(left, 12, w, 16).build());

        if (rows.isEmpty()) {
            // 单屏视图的空状态（总设置入口的区块 A/B 提示行已在 buildRows 里放入）
            CinemaScreen only = ClientScreenManager.get().getScreen(screenId);
            String hint = only == null
                    ? "§7屏幕不存在（可能已被删除）"
                    : "§7「" + only.displayName()
                            + "」队列为空 - 在「📂 本地视频」或「🕘 历史」里点 ＋队列 添加";
            addRenderableWidget(Button.builder(Component.literal(hint), btn -> {})
                    .bounds(left, 36, w, 20).build());
        } else {
            int maxPage = (rows.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(rows.size(), start + ROWS_PER_PAGE);
            int y = 36;
            for (int r = start; r < end; r++) {
                y = renderRow(rows.get(r), left, w, y);
            }

            // 底部固定"清空队列"（两次点击确认）：全局视图清全局队列，单屏视图清该屏队列
            if (isGlobalView()) {
                addRenderableWidget(Button.builder(
                        Component.literal(pendingClearGlobal ? "§c⚠确认清空全局队列?" : "🗑 清空全局队列"),
                        btn -> clearGlobalQueue()
                ).bounds(left, this.height - 56, w, 20).build());
            } else if (ClientScreenManager.get().getScreen(screenId) != null) {
                CinemaScreen s = ClientScreenManager.get().getScreen(screenId);
                addRenderableWidget(Button.builder(
                        Component.literal(pendingClearScreenId != null
                                ? "§c⚠确认清空队列?" : "🗑 清空队列"),
                        btn -> clearQueue(s)
                ).bounds(left, this.height - 56, w, 20).build());
            }

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

    /** 渲染一行（区块标题/组头/提示/条目），返回下一行的 y。 */
    private int renderRow(Row row, int left, int w, int y) {
        switch (row.kind()) {
            case SECTION_TITLE -> {
                // 区块 B 标题：各屏幕队列汇总（位于区块 A 全局队列下方）
                Button title = Button.builder(
                        Component.literal("§7—— 各屏幕队列（汇总） ——"), btn -> {})
                        .bounds(left, y, w, 20).build();
                title.active = false;
                addRenderableWidget(title);
                return y + 22;
            }
            case GLOBAL_EMPTY -> {
                // 区块 A 空提示（文案与原全局视图一致）
                addRenderableWidget(Button.builder(
                        Component.literal("§7全局播放队列为空 - 在总设置的「📂 本地视频」「🕘 播放历史」"
                                + "「🖥 服务器媒体库」里点 ＋队列 添加"), btn -> {})
                        .bounds(left, y, w, 20).build());
                return y + 22;
            }
            case NO_SCREEN -> {
                Button noScreen = Button.builder(
                        Component.literal("§7暂无已知屏幕（创建屏幕后可在此查看各屏队列）"), btn -> {})
                        .bounds(left, y, w, 20).build();
                noScreen.active = false;
                addRenderableWidget(noScreen);
                return y + 22;
            }
            case SCREEN_HEADER -> {
                CinemaScreen s = row.screen();
                if (s == null) return y;
                // 组头：屏幕名 + 坐标 + 队列数量（空队列也显示，队列 0 首）
                String label = "§b📺 " + s.displayName() + " @ " + s.center().toShortString()
                        + "  §7队列 " + QueueClient.countFor(s.id()) + " 首";
                // 总设置入口的组头右侧带"清空该屏队列"（两次点击确认）；单屏视图由底部清空按钮负责
                int labelW = isGlobalView() ? w - 38 : w;
                Button headerBtn = Button.builder(Component.literal(label), btn -> {})
                        .bounds(left, y, labelW, 20).build();
                headerBtn.active = false;
                addRenderableWidget(headerBtn);
                if (isGlobalView()) {
                    String key = s.id().toString();
                    boolean hasEntries = QueueClient.countFor(s.id()) > 0;
                    Button clearBtn = Button.builder(
                            Component.literal(key.equals(pendingClearScreenId) ? "§c⚠确认" : "清空"),
                            btn -> clearQueue(s)
                    ).bounds(left + w - 36, y, 36, 20).build();
                    clearBtn.active = hasEntries || key.equals(pendingClearScreenId);
                    addRenderableWidget(clearBtn);
                }
                return y + 22;
            }
            default -> { }   // 条目行在下方处理
        }

        // 条目行：区块 A 全局条目（entryScreen 为 null）或区块 B/单屏的某屏条目
        boolean globalEntry = row.kind() == RowKind.GLOBAL_ENTRY;
        UUID sid = row.entryScreen();
        int i = row.entryIndex();
        List<QueueEntry> queue = globalEntry
                ? QueueClient.globalEntries() : QueueClient.entriesFor(sid);
        if (i < 0 || i >= queue.size()) return y;   // 数据刚变更：跳过这一行
        QueueEntry entry = queue.get(i);
        String url = entry.url();
        VideoTitleResolver.request(url);

        String playerName = entry.player() == null || entry.player().isEmpty()
                ? "未知玩家" : entry.player();
        String timeText = entry.timeMs() > 0
                ? QUEUE_TIME_FMT.format(Instant.ofEpochMilli(entry.timeMs())
                        .atZone(ZoneId.systemDefault()))
                : "未知时间";
        // 单屏视图在条目里带上屏幕标签；总设置入口由区块组头给出屏幕名（不重复）
        String screenTag = "";
        if (!globalEntry && !isGlobalView()) {
            CinemaScreen qScreen = ClientScreenManager.get().getScreen(sid);
            String screenLabel = (qScreen == null)
                    ? "未知屏幕" : qScreen.displayName() + "@" + qScreen.center().toShortString();
            screenTag = "  §7[" + screenLabel + "]";
        }
        // 行文案：序号 + 绿色标题 +（单屏：所属屏幕 →）加队列玩家 → 加入时间
        String fullName = (i + 1) + ". §a" + ClientConfig.displayNameFor(url)
                + screenTag + "  §7" + playerName + "  " + timeText;
        int nameW = w - 3 * 30 - 6;
        final int index = i;
        Button nameBtn = Button.builder(Component.literal(""), btn -> {
            if (globalEntry) {
                // 区块 A：手动点击 = 向所有屏幕的 owner 发播放申请（两次点击确认）
                globalPlay(index, url);
            } else if (isGlobalView()) {
                // 区块 B 汇总：点条目同样走申请流程（不直接在该屏播放）
                applyAllFromScreenQueue(sid, index, url);
            } else {
                // 单屏视图：点条目名 = 从该屏队列立即播放该项（条目保留，供自动下一个使用）
                ClientNetworkHandlers.sendQueuePlay(sid, index);
            }
        }).bounds(left, y, nameW, 20).build();
        addRenderableWidget(nameBtn);
        // 序号+名称（含标题/备注）：悬停才滚动显示全部
        String shown;
        if (globalEntry && url.equals(pendingGlobalPlayUrl)) {
            shown = "§c⚠ 再点一次: 向所有屏幕发播放申请 ▶ " + ClientConfig.displayNameFor(url);
        } else if (!globalEntry && isGlobalView()
                && (sid + "#" + index).equals(pendingApplyKey)) {
            shown = "§c⚠ 再点一次: 向所有屏幕发播放申请 ▶ " + ClientConfig.displayNameFor(url);
        } else {
            shown = fullName;
        }
        addRenderableWidget(new MarqueeText(left, y, nameW, 20, nameBtn, shown));

        int bx = left + nameW + 2;
        addRenderableWidget(Button.builder(Component.literal("▲"),
                btn -> move(sid, index, -1)
        ).bounds(bx, y, 28, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"),
                btn -> move(sid, index, 1)
        ).bounds(bx + 30, y, 28, 20).build());
        String removeKey = (globalEntry ? "global" : String.valueOf(sid)) + "#" + index;
        addRenderableWidget(Button.builder(
                Component.literal(removeKey.equals(pendingRemoveKey) ? "§c⚠?" : "✕"),
                btn -> {
                    // 删除队列项：两次点击确认（与本地视频库删除同一套机制）
                    if (!removeKey.equals(pendingRemoveKey)) {
                        pendingRemoveKey = removeKey;
                        chat("§c[CinemaForYou] 将删除队列中的这一项（不可恢复），再点一次确认");
                        rebuildWidgets();
                        return;
                    }
                    pendingRemoveKey = null;
                    remove(sid, index);
                }
        ).bounds(bx + 60, y, 28, 20).build());
        return y + 22;
    }

    /** 构建显示行：总设置入口=区块 A 全局队列 + 区块 B 按屏分组的汇总；单屏视图=1 个组头 + 该屏条目。 */
    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        if (isGlobalView()) {
            // ── 区块 A：全局播放队列（原样，不分组、不参与自动连播） ──
            int n = QueueClient.globalCount();
            for (int i = 0; i < n; i++) {
                rows.add(Row.globalEntry(i));
            }
            if (n == 0) {
                rows.add(Row.of(RowKind.GLOBAL_EMPTY));
            }
            // ── 区块 B：各屏幕队列（汇总，按屏幕分组；空队列也保留组头） ──
            rows.add(Row.of(RowKind.SECTION_TITLE));
            List<CinemaScreen> screens =
                    new ArrayList<>(ClientScreenManager.get().allScreens().values());
            screens.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
            if (screens.isEmpty()) {
                rows.add(Row.of(RowKind.NO_SCREEN));
                return rows;
            }
            for (CinemaScreen s : screens) {
                rows.add(Row.screenHeader(s));
                int cn = QueueClient.countFor(s.id());
                for (int i = 0; i < cn; i++) {
                    rows.add(Row.screenEntry(s.id(), i));
                }
            }
            return rows;
        }
        CinemaScreen s = ClientScreenManager.get().getScreen(screenId);
        if (s == null) return rows;
        int n = QueueClient.countFor(screenId);
        if (n == 0) return rows;   // 空队列：由空状态提示行呈现
        rows.add(Row.screenHeader(s));
        for (int i = 0; i < n; i++) {
            rows.add(Row.screenEntry(screenId, i));
        }
        return rows;
    }

    /** 上移/下移：乐观本地更新 + 服务端执行（服务端广播随后覆盖）；sid=null 为全局队列，否则作用于该屏队列。 */
    private void move(UUID sid, int index, int delta) {
        boolean globalScope = sid == null;
        int size = globalScope ? QueueClient.globalCount() : QueueClient.countFor(sid);
        int target = index + delta;
        if (index < 0 || index >= size || target < 0 || target >= size) return;
        if (globalScope) {
            QueueClient.localMoveGlobal(index, target);
            ClientNetworkHandlers.sendGlobalQueueMove(index, target);
        } else {
            QueueClient.localMove(sid, index, target);
            ClientNetworkHandlers.sendQueueMove(sid, index, target);
            pendingApplyKey = null;   // 顺序变更：区块 B 的申请确认作废
        }
        followPage(sid, target);
        rebuildWidgets();
    }

    /** 移动后条目可能落到相邻页：让当前页跟随。 */
    private void followPage(UUID sid, int entryIndex) {
        List<Row> probe = buildRows();
        for (int r = 0; r < probe.size(); r++) {
            Row row = probe.get(r);
            boolean match;
            if (sid == null) {
                match = row.kind() == RowKind.GLOBAL_ENTRY && row.entryIndex() == entryIndex;
            } else {
                match = row.kind() == RowKind.SCREEN_ENTRY
                        && sid.equals(row.entryScreen()) && row.entryIndex() == entryIndex;
            }
            if (match) {
                int p = r / ROWS_PER_PAGE;
                if (p < page) page = Math.max(0, p);
                else if (p > page) page = p;
                return;
            }
        }
    }

    /** 删除：乐观本地更新 + 服务端执行；sid=null 为全局队列，否则作用于该屏队列。 */
    private void remove(UUID sid, int index) {
        boolean globalScope = sid == null;
        int size = globalScope ? QueueClient.globalCount() : QueueClient.countFor(sid);
        if (index < 0 || index >= size) return;
        if (globalScope) {
            QueueClient.localRemoveGlobal(index);
            ClientNetworkHandlers.sendGlobalQueueRemove(index);
        } else {
            QueueClient.localRemove(sid, index);
            ClientNetworkHandlers.sendQueueRemove(sid, index);
            pendingApplyKey = null;   // 下标变更：区块 B 的申请确认作废
        }
        if (!globalScope && QueueClient.countFor(sid) == 0
                && sid.toString().equals(pendingClearScreenId)) {
            pendingClearScreenId = null;   // 队列已空：清空确认状态复位
        }
        int maxPage = Math.max(0, (buildRows().size() - 1) / ROWS_PER_PAGE);
        page = Math.min(page, maxPage);
        rebuildWidgets();
    }

    /** 点全局队列条目：两次点击确认后向所有屏幕的 owner 发播放申请（服务端执行）。 */
    private void globalPlay(int index, String url) {
        if (index < 0 || index >= QueueClient.globalCount()) return;
        if (url == null || !url.equals(pendingGlobalPlayUrl)) {
            pendingGlobalPlayUrl = url;
            chat("§e[CinemaForYou] 将向所有屏幕发送播放申请，再点一次确认");
            rebuildWidgets();
            return;
        }
        pendingGlobalPlayUrl = null;
        ClientNetworkHandlers.sendGlobalQueuePlay(index);
        rebuildWidgets();
    }

    /**
     * 区块 B 汇总视图点某屏队列条目：两次点击确认后向所有屏幕的 owner 发播放申请
     * （与区块 A 同一路径，<b>不</b>直接在该屏播放；管理操作仍作用于该屏队列）。
     */
    private void applyAllFromScreenQueue(UUID sid, int index, String url) {
        if (sid == null || url == null || url.isEmpty()) return;
        if (index < 0 || index >= QueueClient.countFor(sid)) return;
        String key = sid + "#" + index;
        if (!key.equals(pendingApplyKey)) {
            pendingApplyKey = key;
            chat("§e[CinemaForYou] 将向所有屏幕发送播放申请，再点一次确认");
            rebuildWidgets();
            return;
        }
        pendingApplyKey = null;
        ScreenSoundSettingsScreen.playOnAll(url);
        rebuildWidgets();
    }

    /** 清空全局播放队列：两次点击确认。 */
    private void clearGlobalQueue() {
        if (!pendingClearGlobal) {
            pendingClearGlobal = true;
            chat("§c[CinemaForYou] 将清空全局播放队列（不可恢复），再点一次确认");
            rebuildWidgets();
            return;
        }
        pendingClearGlobal = false;
        QueueClient.localClearGlobal();
        ClientNetworkHandlers.sendGlobalQueueClear();
        page = 0;
        rebuildWidgets();
    }

    /** 清空某屏队列：两次点击确认（与本地视频库删除同一套机制）。 */
    private void clearQueue(CinemaScreen s) {
        if (s == null) return;
        String key = s.id().toString();
        if (!key.equals(pendingClearScreenId)) {
            pendingClearScreenId = key;
            chat("§c[CinemaForYou] 将清空该屏播放队列（不可恢复），再点一次确认");
            rebuildWidgets();
            return;
        }
        pendingClearScreenId = null;
        pendingApplyKey = null;   // 队列已清空：该屏的申请确认作废
        QueueClient.localClear(s.id());
        ClientNetworkHandlers.sendQueueClear(s.id());
        if (!isGlobalView()) {
            page = 0;   // 单屏视图原行为：清空后回到第一页
        } else {
            // 总设置入口：清空某屏队列后行数变少，当前页跟随收敛
            page = Math.min(page, Math.max(0, (buildRows().size() - 1) / ROWS_PER_PAGE));
        }
        rebuildWidgets();
    }

    private static void chat(String msg) {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.sendSystemMessage(Component.literal(msg));
        }
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
