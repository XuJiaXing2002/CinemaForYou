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
 *   <li>无参构造：总设置的「全局播放队列管理」入口——只展示全局播放队列（单一列表）。
 *       条目<b>不参与任何屏幕的自动连播</b>，管理员手动点击某条才向所有屏幕的 owner
 *       发送播放申请（两次点击确认）；</li>
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

    /** 目标屏幕；null = 全局播放队列（总设置入口）。 */
    private final UUID screenId;
    private int page = 0;
    /** 二次确认状态（与本地视频库一致的机制）：待删除条目键（"屏幕UUID#下标" / "global#下标"）。 */
    private String pendingRemoveKey = null;
    /** 二次确认状态：待清空队列的屏幕 UUID 字符串。 */
    private String pendingClearScreenId = null;
    /** 二次确认状态：全局播放队列待清空。 */
    private boolean pendingClearGlobal = false;
    /** 全局视图二次确认：待确认"向所有屏幕发播放申请"的条目 URL。 */
    private String pendingGlobalPlayUrl = null;

    /** 列表行：组头行（header != null）或条目行（entryScreen + entryIndex；全局行 entryScreen 为 null）。 */
    private record Row(CinemaScreen header, UUID entryScreen, int entryIndex) {}

    /** 全部屏幕视图：按屏幕分组后的行列表（只列出队列非空的屏幕）。 */
    public ScreenQueueManagerScreen() {
        this(null);
    }

    /**
     * @param screenId 只管理该屏队列（点条目=该屏播放）；null = 全局播放队列（点条目=向所有屏幕发播放申请）
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
        int entries = 0;
        List<Row> rows = buildRows();
        for (Row r : rows) {
            if (r.header() == null) entries++;
        }

        addRenderableWidget(Button.builder(
                Component.literal(isGlobalView()
                        ? "§e📋 全局播放队列（共 " + entries
                                + " 首）— 点条目向所有屏幕发播放申请（不参与自动连播）"
                        : "§e📋 播放队列（" + entries + " 首）— 播完=自动播放下一个时按此顺序循环"),
                btn -> {}
        ).bounds(left, 12, w, 16).build());

        if (rows.isEmpty()) {
            String hint;
            if (isGlobalView()) {
                hint = "§7全局播放队列为空 - 在总设置的「📂 本地视频」「🕘 播放历史」"
                        + "「🖥 服务器媒体库」里点 ＋队列 添加";
            } else {
                CinemaScreen only = ClientScreenManager.get().getScreen(screenId);
                hint = only == null
                        ? "§7屏幕不存在（可能已被删除）"
                        : "§7「" + only.displayName()
                                + "」队列为空 - 在「📂 本地视频」或「🕘 历史」里点 ＋队列 添加";
            }
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

    /** 渲染一行（组头或条目），返回下一行的 y。 */
    private int renderRow(Row row, int left, int w, int y) {
        CinemaScreen header = row.header();
        if (header != null) {
            // 组头：屏幕名 + 坐标 + 队列数量（全部视图右侧带"清空"）
            String label = "§b📺 " + header.displayName() + " @ " + header.center().toShortString()
                    + "  §7队列 " + QueueClient.countFor(header.id()) + " 首";
            Button headerBtn = Button.builder(Component.literal(label), btn -> {})
                    .bounds(left, y, w, 20).build();
            headerBtn.active = false;
            addRenderableWidget(headerBtn);
            return y + 22;
        }

        // 全局视图：单一列表；单屏视图：该屏队列
        UUID sid = row.entryScreen();
        int i = row.entryIndex();
        List<QueueEntry> queue = isGlobalView()
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
        // 单屏视图在条目里带上屏幕标签；全局视图由标题给出范围
        String screenTag = "";
        if (!isGlobalView()) {
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
            if (isGlobalView()) {
                // 全局队列：手动点击 = 向所有屏幕的 owner 发播放申请（两次点击确认）
                globalPlay(index, url);
            } else {
                // 单屏视图：点条目名 = 从该屏队列立即播放该项（条目保留，供自动下一个使用）
                ClientNetworkHandlers.sendQueuePlay(sid, index);
            }
        }).bounds(left, y, nameW, 20).build();
        addRenderableWidget(nameBtn);
        // 序号+名称（含标题/备注）：悬停才滚动显示全部
        String shown = isGlobalView() && url.equals(pendingGlobalPlayUrl)
                ? "§c⚠ 再点一次: 向所有屏幕发播放申请 ▶ " + ClientConfig.displayNameFor(url)
                : fullName;
        addRenderableWidget(new MarqueeText(left, y, nameW, 20, nameBtn, shown));

        int bx = left + nameW + 2;
        addRenderableWidget(Button.builder(Component.literal("▲"),
                btn -> move(sid, index, -1)
        ).bounds(bx, y, 28, 20).build());
        addRenderableWidget(Button.builder(Component.literal("▼"),
                btn -> move(sid, index, 1)
        ).bounds(bx + 30, y, 28, 20).build());
        String removeKey = (isGlobalView() ? "global" : String.valueOf(sid)) + "#" + index;
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

    /** 构建显示行：全局视图=单一全局队列；单屏视图=1 个组头 + 该屏条目。 */
    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        if (isGlobalView()) {
            // 全局播放队列：不分组、不参与自动连播
            int n = QueueClient.globalCount();
            for (int i = 0; i < n; i++) {
                rows.add(new Row(null, null, i));
            }
            return rows;
        }
        CinemaScreen s = ClientScreenManager.get().getScreen(screenId);
        if (s == null) return rows;
        int n = QueueClient.countFor(screenId);
        if (n == 0) return rows;   // 空队列：由空状态提示行呈现
        rows.add(new Row(s, null, -1));
        for (int i = 0; i < n; i++) {
            rows.add(new Row(null, screenId, i));
        }
        return rows;
    }

    /** 上移/下移：乐观本地更新 + 服务端执行（服务端广播随后覆盖）。 */
    private void move(UUID sid, int index, int delta) {
        int size = isGlobalView() ? QueueClient.globalCount() : QueueClient.countFor(sid);
        int target = index + delta;
        if (index < 0 || index >= size || target < 0 || target >= size) return;
        if (isGlobalView()) {
            QueueClient.localMoveGlobal(index, target);
            ClientNetworkHandlers.sendGlobalQueueMove(index, target);
        } else {
            QueueClient.localMove(sid, index, target);
            ClientNetworkHandlers.sendQueueMove(sid, index, target);
        }
        followPage(sid, target);
        rebuildWidgets();
    }

    /** 移动后条目可能落到相邻页：让当前页跟随。 */
    private void followPage(UUID sid, int entryIndex) {
        List<Row> probe = buildRows();
        for (int r = 0; r < probe.size(); r++) {
            Row row = probe.get(r);
            if (row.header() != null) continue;
            boolean match = isGlobalView()
                    ? row.entryIndex() == entryIndex
                    : (sid != null && sid.equals(row.entryScreen()) && row.entryIndex() == entryIndex);
            if (match) {
                int p = r / ROWS_PER_PAGE;
                if (p < page) page = Math.max(0, p);
                else if (p > page) page = p;
                return;
            }
        }
    }

    /** 删除：乐观本地更新 + 服务端执行。 */
    private void remove(UUID sid, int index) {
        int size = isGlobalView() ? QueueClient.globalCount() : QueueClient.countFor(sid);
        if (index < 0 || index >= size) return;
        if (isGlobalView()) {
            QueueClient.localRemoveGlobal(index);
            ClientNetworkHandlers.sendGlobalQueueRemove(index);
        } else {
            QueueClient.localRemove(sid, index);
            ClientNetworkHandlers.sendQueueRemove(sid, index);
        }
        if (!isGlobalView() && QueueClient.countFor(sid) == 0
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
        QueueClient.localClear(s.id());
        ClientNetworkHandlers.sendQueueClear(s.id());
        page = 0;
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
