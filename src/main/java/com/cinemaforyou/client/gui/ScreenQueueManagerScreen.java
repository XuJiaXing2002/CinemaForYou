package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.network.QueueClient;
import com.cinemaforyou.client.network.ScreenAdminClient;
import com.cinemaforyou.client.video.VideoTitleResolver;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.network.QueueEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
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
 * 播放队列管理（服务端队列的客户端只读镜像 + 操作入口）。
 *
 * <p>顶部统一标题行「播放队列 → 向所有屏幕发送播放申请」（与原来一致：黄色文字、无按钮；
 * 原独立的「向所有屏幕发送播放申请」区块标题行与「全局列表为空」空状态行已按要求合并/删除）。
 * 标题下方为搜索框（沿用 {@link TargetSearchScreen} 的实现风格与位置），
 * 过滤当前层级的列表：玩家名 / 屏幕名 / 队列视频标题。
 *
 * <p>两种入口共五种视图：
 * <ul>
 *   <li>总设置入口 {@link #ScreenQueueManagerScreen()} ＝三级：
 *       <b>主页</b>一行一个玩家（玩家名 + 屏幕数 + 队列数），最上方固定一行
 *       「🎬 全局播放队列（N 首）」入口；点玩家 → 该玩家的屏幕列表 {@link #forPlayer}，
 *       点屏幕 → 该屏队列明细 {@link #ScreenQueueManagerScreen(UUID)}（点条目 = 从该屏队列立即播放）；
 *       点「全局播放队列」行 → 全局专用队列明细 {@link #forGlobalQueue()}
 *       （原区块 A：管理员手动点条目，两次点击确认后向所有屏幕的 owner 发送播放申请）；</li>
 *   <li>屏幕控制入口 {@link #forScreenList()} ＝两级：主页即全部屏幕列表（不显示玩家层级），
 *       点屏幕 → 该屏队列明细。</li>
 * </ul>
 *
 * <p>队列明细沿用原渲染与按钮（▲▼ 调序、✕ 删除、点条目名播放/发申请），
 * 删除/清空/上移下移都发到服务端执行（服务端为唯一数据源：屏幕队列 owner 或 OP≥2 可操作，
 * 全局队列仅 OP≥2），删除与清空沿用两次点击确认。
 *
 * <p>权限（与服务端 {@code ScreenManager.canControl} 一致）：非本人屏幕且非 OP≥2 时，
 * 队列的增（＋队列入口）/删（✕）/清空/上移下移一律置灰并显示灰字提示；
 * 队列查看与点条目播放/发播放申请不受限（播放由服务端自动走播放申请）。
 */
public class ScreenQueueManagerScreen extends Screen {
    @Override
    public void onClose() {
        if (!GuiNav.back(this)) {
            super.onClose();
        }
    }

    /** 每页行数（顶部新增搜索框后由 8 调整为 7，分页机制不变）。 */
    private static final int ROWS_PER_PAGE = 7;
    /** 列表起始 y：标题（2..14）+ 搜索框（17..37）之后，与 TargetSearchScreen 的布局一致。 */
    private static final int ROWS_TOP = 41;
    /** 顶部统一标题行文案（黄色文字、无按钮）。 */
    private static final String TITLE_TEXT = "播放队列 → 向所有屏幕发送播放申请";
    /** 队列条目"加入时间"显示格式（与播放历史一致）。 */
    private static final DateTimeFormatter QUEUE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 界面视图（两种入口的层级由它区分）。 */
    private enum View {
        /** 总设置入口主页：玩家列表 + 全局专用队列入口行。 */
        HOME,
        /** 总设置入口第二级：某玩家的屏幕列表。 */
        PLAYER_SCREENS,
        /** 屏幕控制入口第一级：全部屏幕列表（不显示玩家层级）。 */
        SCREEN_LIST,
        /** 某屏队列明细（总设置入口第三级 / 屏幕控制入口第二级）。 */
        SCREEN_DETAIL,
        /** 全局专用队列明细（原区块 A）。 */
        GLOBAL_DETAIL
    }

    private final View view;
    /** SCREEN_DETAIL：目标屏幕；其它视图为 null。 */
    private final UUID screenId;
    /** PLAYER_SCREENS：玩家分组键（屏幕 ownerId；"" = "未知玩家"）与显示名。 */
    private final String ownerKey;
    private final String ownerLabel;

    /** 当前层级的搜索关键字（按玩家名/屏幕名/视频标题过滤）。 */
    private String query = "";
    private EditBox searchBox;
    private int page = 0;
    /** 二次确认状态（与本地视频库一致的机制）：待删除条目键（"屏幕UUID#下标" / "global#下标"）。 */
    private String pendingRemoveKey = null;
    /** 二次确认状态：待清空队列的屏幕 UUID 字符串。 */
    private String pendingClearScreenId = null;
    /** 二次确认状态：全局专用队列待清空。 */
    private boolean pendingClearGlobal = false;
    /** 全局专用队列二次确认：待确认"向所有屏幕发播放申请"的条目 URL。 */
    private String pendingGlobalPlayUrl = null;

    /** 列表行类型：玩家分组/表头、全局专用队列入口/表头、屏幕汇总行/组头、条目行。 */
    private enum RowKind {
        PLAYER_GROUP, PLAYER_HEADER, GLOBAL_QUEUE_LINK, GLOBAL_HEADER,
        SCREEN_SUMMARY, SCREEN_HEADER, SCREEN_ENTRY, GLOBAL_ENTRY
    }

    /** 列表行：玩家分组行 / 全局专用队列入口行 / 屏幕行 / 组头行 / 条目行。 */
    private record Row(RowKind kind, PlayerGroup group, CinemaScreen screen,
                       UUID entryScreen, int entryIndex) {
        /** 全局专用队列入口（一行汇总）。 */
        static Row globalQueueLink() {
            return new Row(RowKind.GLOBAL_QUEUE_LINK, null, null, null, -1);
        }

        /** 主页：一行一个玩家。 */
        static Row playerGroup(PlayerGroup g) {
            return new Row(RowKind.PLAYER_GROUP, g, null, null, -1);
        }

        /** 某玩家屏幕列表页的黄色表头。 */
        static Row playerHeader(PlayerGroup g) {
            return new Row(RowKind.PLAYER_HEADER, g, null, null, -1);
        }

        /** 全局专用队列明细页的黄色表头。 */
        static Row globalHeader() {
            return new Row(RowKind.GLOBAL_HEADER, null, null, null, -1);
        }

        /** 屏幕列表页：一行一个屏幕（点击进入该屏队列明细）。 */
        static Row screenSummary(CinemaScreen s) {
            return new Row(RowKind.SCREEN_SUMMARY, null, s, null, -1);
        }

        /** 单屏明细页组头。 */
        static Row screenHeader(CinemaScreen s) {
            return new Row(RowKind.SCREEN_HEADER, null, s, null, -1);
        }

        /** 某屏队列条目（entryScreen = 该屏 UUID）。 */
        static Row screenEntry(UUID screenId, int index) {
            return new Row(RowKind.SCREEN_ENTRY, null, null, screenId, index);
        }

        /** 全局专用队列条目（entryScreen 为 null）。 */
        static Row globalEntry(int index) {
            return new Row(RowKind.GLOBAL_ENTRY, null, null, null, index);
        }
    }

    /** 总设置入口：全局播放队列主页（玩家列表 + 全局专用队列入口行）。 */
    public ScreenQueueManagerScreen() {
        this(View.HOME, null, null, null);
    }

    /** @param screenId 只管理该屏队列的明细页（点条目 = 从该屏队列立即播放） */
    public ScreenQueueManagerScreen(UUID screenId) {
        this(View.SCREEN_DETAIL, screenId, null, null);
    }

    private ScreenQueueManagerScreen(View view, UUID screenId, String ownerKey, String ownerLabel) {
        // 标题统一：「播放队列」——总设置入口 / 屏幕控制入口 / 各级明细页一致
        super(Component.literal("播放队列"));
        this.view = view;
        this.screenId = screenId;
        this.ownerKey = ownerKey;
        this.ownerLabel = ownerLabel;
    }

    /** 总设置入口第二级：某玩家的屏幕列表（点屏幕 → 该屏队列明细）。 */
    public static ScreenQueueManagerScreen forPlayer(String ownerKey, String ownerLabel) {
        return new ScreenQueueManagerScreen(View.PLAYER_SCREENS, null, ownerKey, ownerLabel);
    }

    /** 屏幕控制入口第一级：全部屏幕列表（不显示玩家层级；点屏幕 → 该屏队列明细）。 */
    public static ScreenQueueManagerScreen forScreenList() {
        return new ScreenQueueManagerScreen(View.SCREEN_LIST, null, null, null);
    }

    /** 全局专用队列明细（原区块 A：点条目两次确认后向所有屏幕发播放申请）。 */
    public static ScreenQueueManagerScreen forGlobalQueue() {
        return new ScreenQueueManagerScreen(View.GLOBAL_DETAIL, null, null, null);
    }

    @Override
    protected void init() {
        // 玩家分组显示名来自屏幕元数据（ownerName）；到达后由监听器重建界面
        ClientNetworkHandlers.requestScreenMeta();
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        // 服务端队列广播到达后刷新；标题补全后也刷新列表
        QueueClient.setListener(() -> Minecraft.getInstance().execute(this::rebuildWidgets));
        VideoTitleResolver.setListener(() ->
                Minecraft.getInstance().execute(this::rebuildWidgets));
        // 屏幕元数据（玩家显示名）到达后刷新玩家分组
        ScreenAdminClient.setListener(() ->
                Minecraft.getInstance().execute(this::rebuildWidgets));

        int cx = this.width / 2;
        int w = Math.min(320, this.width - 30);
        int left = cx - w / 2;
        List<Row> rows = buildRows();

        // 顶部标题行：整体黄色文字标题（GuiTextLabel，无按钮/灰框），贴屏幕顶部；
        // 「播放队列 → 向所有屏幕发送播放申请」——原独立区块标题行已合并到这里
        addRenderableWidget(new GuiTextLabel(cx, 2, w, 12,
                TITLE_TEXT,
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));

        // 搜索框（与 TargetSearchScreen 同一套实现/位置）：过滤当前层级的列表
        searchBox = new EditBox(this.font, left, 17, w - 62, 20,
                Component.literal("搜索"));
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.literal("🔍"),
                btn -> {
                    query = searchBox.getValue() == null ? "" : searchBox.getValue().trim();
                    page = 0;
                    rebuildWidgets();
                }
        ).bounds(left + w - 58, 17, 58, 20).build());

        // 非本人屏幕（且非 OP）时：队列操作置灰，顶部加一行灰字提示（查看/点播不受限）
        boolean restrictedScope = !canOperateScope();
        int rowsTop = ROWS_TOP + (restrictedScope ? 13 : 0);
        if (restrictedScope) {
            addRenderableWidget(new GuiTextLabel(cx, ROWS_TOP, w, 12,
                    "非本人屏幕不可操作：队列仅可查看/点播（增删/清空/排序需 owner 或 OP≥2）",
                    GuiTextLabel.Align.CENTER, GuiTextLabel.GRAY_LIGHT));
        }

        if (rows.isEmpty()) {
            // 空状态提示（全局专用队列明细已按要求不再显示空状态行，此处返回 null）
            String hint = emptyHint();
            if (hint != null) {
                addRenderableWidget(Button.builder(Component.literal(hint), btn -> {})
                        .bounds(left, rowsTop, w, 20).build());
            }
        } else {
            int maxPage = (rows.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(rows.size(), start + ROWS_PER_PAGE);
            int y = rowsTop;
            for (int r = start; r < end; r++) {
                y = renderRow(rows.get(r), left, w, y);
            }

            // 底部固定"清空队列"（两次点击确认）：全局专用队列明细清全局队列，单屏明细清该屏队列
            if (view == View.GLOBAL_DETAIL) {
                // 全局队列仅 OP≥2 可清空（服务端同样校验）：非 OP 置灰
                Button clearGlobalBtn = Button.builder(
                        Component.literal(pendingClearGlobal ? "§c⚠确认清空全局队列?" : "🗑 清空全局队列"),
                        btn -> clearGlobalQueue()
                ).bounds(left, this.height - 56, w, 20).build();
                clearGlobalBtn.active = ScreenControlScreen.hasOpPermission();
                addRenderableWidget(clearGlobalBtn);
            } else if (view == View.SCREEN_DETAIL && targetScreen() != null) {
                CinemaScreen s = targetScreen();
                // 清空该屏队列：会改动屏幕状态，非 owner 且非 OP 置灰
                Button clearScreenBtn = Button.builder(
                        Component.literal(pendingClearScreenId != null
                                ? "§c⚠确认清空队列?" : "🗑 清空队列"),
                        btn -> clearQueue(s)
                ).bounds(left, this.height - 56, w, 20).build();
                clearScreenBtn.active = ScreenControlScreen.canManageScreen(s);
                addRenderableWidget(clearScreenBtn);
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

    /**
     * 当前视图是否有权改动队列：单屏明细看该屏是否可管理（owner 或 OP≥2）；
     * 全局专用队列仅 OP≥2；玩家/屏幕列表视图逐行判定（见 {@link #renderRow}）。
     */
    private boolean canOperateScope() {
        if (view == View.SCREEN_DETAIL) return ScreenControlScreen.canManageScreen(screenId);
        if (view == View.GLOBAL_DETAIL) return ScreenControlScreen.hasOpPermission();
        return true;
    }

    /** 当前层级的空状态提示（无提示返回 null）。 */
    private String emptyHint() {
        boolean searching = !normalizedQuery().isEmpty();
        if (view == View.SCREEN_DETAIL) {
            if (searching) return "§7没有匹配的队列条目";
            CinemaScreen s = targetScreen();
            return s == null
                    ? "§7屏幕不存在（可能已被删除）"
                    : "§7「" + s.displayName()
                            + "」队列为空：在「📂 本地视频」或「🕘 历史」里点 ＋队列 添加";
        }
        if (view == View.GLOBAL_DETAIL) {
            // 全局专用队列的空状态行已按要求删除：仅在搜索无结果时提示
            return searching ? "§7没有匹配的队列条目" : null;
        }
        return searching ? "§7没有匹配的屏幕" : "§7暂无已知屏幕（创建屏幕后可在此查看各屏队列）";
    }

    /** 渲染一行（入口/表头/组头/提示/条目），返回下一行的 y。 */
    private int renderRow(Row row, int left, int w, int y) {
        switch (row.kind()) {
            case GLOBAL_QUEUE_LINK -> {
                // 全局专用队列入口（原区块 A）：一行汇总 + 数量；点进去是该队列明细，
                // 明细管理与"点条目→两次确认→向所有屏幕发申请"逻辑保持现状。
                // 该行固定在主页最上方，不参与搜索过滤（保证入口不丢）。
                String label = UiText.fit(
                        "§b🎬 全局播放队列（" + QueueClient.globalCount() + " 首）", w - 10);
                addRenderableWidget(Button.builder(Component.literal(label),
                        btn -> GuiNav.open(this, ScreenQueueManagerScreen.forGlobalQueue()))
                        .bounds(left, y, w, 20).build());
                return y + 22;
            }
            case PLAYER_GROUP -> {
                PlayerGroup g = row.group();
                if (g == null) return y;
                // 一行一个玩家：玩家名 + 屏幕数 + 队列数；点整行 → 该玩家的屏幕列表
                String label = UiText.fit("§b" + g.label() + "  §7屏幕 " + g.screens().size()
                        + " 个  §7队列 " + queueCountOf(g.screens()) + " 首", w - 10);
                addRenderableWidget(Button.builder(Component.literal(label),
                        btn -> GuiNav.open(this, ScreenQueueManagerScreen.forPlayer(g.key(), g.label())))
                        .bounds(left, y, w, 20).build());
                return y + 22;
            }
            case PLAYER_HEADER -> {
                // 某玩家屏幕列表页表头：黄色文字标题（与单屏明细页组头同一风格）
                PlayerGroup g = row.group();
                if (g == null) return y;
                // 显示名与主页玩家行保持一致：优先用上一级传入的玩家名
                String pName = (ownerLabel == null || ownerLabel.isEmpty()) ? g.label() : ownerLabel;
                addRenderableWidget(new GuiTextLabel(this.width / 2, y + 4, w, 12,
                        "👤 " + pName + "  屏幕 " + g.screens().size()
                                + " 个  队列 " + queueCountOf(g.screens()) + " 首",
                        GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
                return y + 22;
            }
            case GLOBAL_HEADER -> {
                // 全局专用队列明细页表头：黄色文字标题
                addRenderableWidget(new GuiTextLabel(this.width / 2, y + 4, w, 12,
                        "🎬 全局播放队列（" + QueueClient.globalCount() + " 首）",
                        GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
                return y + 22;
            }
            case SCREEN_SUMMARY -> {
                CinemaScreen s = row.screen();
                if (s == null) return y;
                // 屏幕列表：一行一个屏幕（空队列也显示，队列 0 首），文案 = 屏幕名 + 坐标 + 队列数量
                // 点整行 → 进入该屏队列明细页（条目渲染/按钮逻辑完全不变）
                String label = "§b📺 " + s.displayName() + " @ " + s.center().toShortString()
                        + "  §7队列 " + QueueClient.countFor(s.id()) + " 首";
                addRenderableWidget(Button.builder(Component.literal(label),
                        btn -> GuiNav.open(this, new ScreenQueueManagerScreen(s.id())))
                        .bounds(left, y, w - 38, 20).build());
                // 行右侧保留"清空该屏队列"（两次点击确认，与原来一致）；非本人屏幕且非 OP 置灰
                String key = s.id().toString();
                boolean hasEntries = QueueClient.countFor(s.id()) > 0;
                Button clearBtn = Button.builder(
                        Component.literal(key.equals(pendingClearScreenId) ? "§c⚠确认" : "清空"),
                        btn -> clearQueue(s)
                ).bounds(left + w - 36, y, 36, 20).build();
                clearBtn.active = ScreenControlScreen.canManageScreen(s)
                        && (hasEntries || key.equals(pendingClearScreenId));
                addRenderableWidget(clearBtn);
                return y + 22;
            }
            case SCREEN_HEADER -> {
                // 单屏明细页组头：统一为黄色文字标题（原灰色不可点击按钮框已替换，行为不变）
                CinemaScreen s = row.screen();
                if (s == null) return y;
                addRenderableWidget(new GuiTextLabel(this.width / 2, y + 4, w, 12,
                        "📺 " + s.displayName() + " @ " + s.center().toShortString()
                                + "  队列 " + QueueClient.countFor(s.id()) + " 首",
                        GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
                return y + 22;
            }
            default -> { }   // 条目行在下方处理
        }

        // 条目行：全局专用队列条目（entryScreen 为 null）或某屏队列条目
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
        // 屏幕明细页的条目带上所属屏幕标签（全局专用队列条目没有屏幕）
        String screenTag = "";
        if (!globalEntry) {
            CinemaScreen qScreen = ClientScreenManager.get().getScreen(sid);
            String screenLabel = (qScreen == null)
                    ? "未知屏幕" : qScreen.displayName() + "@" + qScreen.center().toShortString();
            screenTag = "  §7[" + screenLabel + "]";
        }
        // 行文案：序号 + 绿色标题 +（屏幕明细：所属屏幕 →）加队列玩家 → 加入时间
        String fullName = (i + 1) + ". §a" + ClientConfig.displayNameFor(url)
                + screenTag + "  §7" + playerName + "  " + timeText;
        int nameW = w - 3 * 30 - 6;
        final int index = i;
        Button nameBtn = Button.builder(Component.literal(""), btn -> {
            if (globalEntry) {
                // 全局专用队列：手动点击 = 向所有屏幕的 owner 发播放申请（两次点击确认）
                globalPlay(index, url);
            } else {
                // 屏幕明细页：点条目名 = 从该屏队列立即播放该项（条目保留，供自动下一个使用）
                ClientNetworkHandlers.sendQueuePlay(sid, index);
            }
        }).bounds(left, y, nameW, 20).build();
        addRenderableWidget(nameBtn);
        // 序号+名称（含标题/备注）：悬停才滚动显示全部
        String shown;
        if (globalEntry && url.equals(pendingGlobalPlayUrl)) {
            shown = "§c⚠ 再点一次: 向所有屏幕发播放申请 ▶ " + ClientConfig.displayNameFor(url);
        } else {
            shown = fullName;
        }
        addRenderableWidget(new MarqueeText(left, y, nameW, 20, nameBtn, shown));

        // 上移/下移/删除：会改动队列（对方屏幕状态）——非 owner 且非 OP 置灰；
        // 条目名按钮（点播/发申请）与查看不受限，保持可用
        boolean canOperateEntries = globalEntry
                ? ScreenControlScreen.hasOpPermission()
                : ScreenControlScreen.canManageScreen(sid);
        int bx = left + nameW + 2;
        Button upBtn = Button.builder(Component.literal("▲"),
                btn -> move(sid, index, -1)
        ).bounds(bx, y, 28, 20).build();
        upBtn.active = canOperateEntries;
        addRenderableWidget(upBtn);
        Button downBtn = Button.builder(Component.literal("▼"),
                btn -> move(sid, index, 1)
        ).bounds(bx + 30, y, 28, 20).build();
        downBtn.active = canOperateEntries;
        addRenderableWidget(downBtn);
        String removeKey = (globalEntry ? "global" : String.valueOf(sid)) + "#" + index;
        Button removeBtn = Button.builder(
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
        ).bounds(bx + 60, y, 28, 20).build();
        removeBtn.active = canOperateEntries;
        addRenderableWidget(removeBtn);
        return y + 22;
    }

    /**
     * 构建显示行（随视图与搜索关键字变化）：
     * 主页＝全局专用队列入口 + 玩家列表；玩家屏幕列表／屏幕控制屏幕列表＝表头 + 每屏一行；
     * 明细页＝组头 + 当前队列条目（全局专用队列条目无屏幕标签）。
     */
    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        switch (view) {
            case HOME -> {
                // 全局专用队列入口固定在主页最上方（不参与搜索过滤，保证入口不丢）
                rows.add(Row.globalQueueLink());
                for (PlayerGroup g : filteredPlayerGroups()) {
                    rows.add(Row.playerGroup(g));
                }
            }
            case PLAYER_SCREENS -> {
                PlayerGroup g = playerGroupOf(ownerKey);
                if (g == null) return rows;
                rows.add(Row.playerHeader(g));
                for (CinemaScreen s : filteredScreens(g.screens())) {
                    rows.add(Row.screenSummary(s));
                }
            }
            case SCREEN_LIST -> {
                for (CinemaScreen s : filteredScreens(allScreens())) {
                    rows.add(Row.screenSummary(s));
                }
            }
            case SCREEN_DETAIL -> {
                CinemaScreen s = targetScreen();
                if (s == null) return rows;
                List<Integer> idx = filteredEntryIndexes(QueueClient.entriesFor(screenId));
                if (idx.isEmpty()) return rows;   // 空队列：由空状态提示行呈现
                rows.add(Row.screenHeader(s));
                for (int i : idx) {
                    rows.add(Row.screenEntry(screenId, i));
                }
            }
            case GLOBAL_DETAIL -> {
                // 空队列：不显示任何行（原空状态提示行已按用户要求删除）
                List<Integer> idx = filteredEntryIndexes(QueueClient.globalEntries());
                if (idx.isEmpty()) return rows;
                rows.add(Row.globalHeader());
                for (int i : idx) {
                    rows.add(Row.globalEntry(i));
                }
            }
        }
        return rows;
    }

    // ───────────── 搜索过滤（玩家名 / 屏幕名 / 视频标题） ─────────────

    private String normalizedQuery() {
        return query == null ? "" : query.trim().toLowerCase();
    }

    /** 屏幕是否命中搜索：屏幕名/自定义名/创建者/该屏队列的视频标题或入队玩家。 */
    private static boolean screenMatches(CinemaScreen s, String q) {
        if (s.displayName() != null && s.displayName().toLowerCase().contains(q)) return true;
        if (s.customId() != null && s.customId().toLowerCase().contains(q)) return true;
        String owner = ScreenAdminClient.ownerOf(s.id());
        if (owner != null && owner.toLowerCase().contains(q)) return true;
        for (QueueEntry e : QueueClient.entriesFor(s.id())) {
            if (entryMatches(e, q)) return true;
        }
        return false;
    }

    /** 队列条目是否命中搜索：视频标题（备注→标题→链接）+ 入队玩家名。 */
    private static boolean entryMatches(QueueEntry e, String q) {
        if (e == null) return false;
        if (ClientConfig.displayNameFor(e.url()).toLowerCase().contains(q)) return true;
        return e.player() != null && e.player().toLowerCase().contains(q);
    }

    private List<CinemaScreen> filteredScreens(List<CinemaScreen> screens) {
        String q = normalizedQuery();
        if (q.isEmpty()) return screens;
        List<CinemaScreen> out = new ArrayList<>();
        for (CinemaScreen s : screens) {
            if (screenMatches(s, q)) out.add(s);
        }
        return out;
    }

    private List<PlayerGroup> filteredPlayerGroups() {
        String q = normalizedQuery();
        List<PlayerGroup> out = new ArrayList<>();
        for (PlayerGroup g : playerGroups()) {
            if (q.isEmpty() || g.label().toLowerCase().contains(q)
                    || anyScreenMatches(g.screens(), q)) {
                out.add(g);
            }
        }
        return out;
    }

    private static boolean anyScreenMatches(List<CinemaScreen> screens, String q) {
        for (CinemaScreen s : screens) {
            if (screenMatches(s, q)) return true;
        }
        return false;
    }

    /** 队列条目里命中搜索的下标（保留下标原值：删除/上下移仍作用于服务端列表的真实位置）。 */
    private List<Integer> filteredEntryIndexes(List<QueueEntry> list) {
        String q = normalizedQuery();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (q.isEmpty() || entryMatches(list.get(i), q)) out.add(i);
        }
        return out;
    }

    // ───────────── 玩家分组（与屏幕列表管理/选择屏幕同一套分组规则） ─────────────

    /** 玩家分组：key = ownerId（"" = 未知玩家），label = 显示名，screens = 该玩家屏幕。 */
    private record PlayerGroup(String key, String label, List<CinemaScreen> screens) {}

    /** 全部已知屏幕（按屏幕名排序）。 */
    private static List<CinemaScreen> allScreens() {
        List<CinemaScreen> screens =
                new ArrayList<>(ClientScreenManager.get().allScreens().values());
        screens.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return screens;
    }

    /** 按 owner 分组（一行一个玩家），组按玩家名排序。 */
    private static List<PlayerGroup> playerGroups() {
        Map<String, List<CinemaScreen>> map = new LinkedHashMap<>();
        for (CinemaScreen s : allScreens()) {
            map.computeIfAbsent(ownerKeyOf(s), k -> new ArrayList<>()).add(s);
        }
        List<PlayerGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<CinemaScreen>> e : map.entrySet()) {
            out.add(new PlayerGroup(e.getKey(), ownerLabelOf(e.getValue()), e.getValue()));
        }
        out.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        return out;
    }

    private static PlayerGroup playerGroupOf(String key) {
        if (key == null) return null;
        for (PlayerGroup g : playerGroups()) {
            if (g.key().equals(key)) return g;
        }
        return null;
    }

    /** 分组键：屏幕 ownerId（缺失/为空归入 ""="未知玩家"）。 */
    private static String ownerKeyOf(CinemaScreen s) {
        return s.ownerId() == null ? "" : s.ownerId();
    }

    /** 分组显示名：取组内任一屏幕的创建者元数据名；没有则"未知玩家"。 */
    private static String ownerLabelOf(List<CinemaScreen> screens) {
        for (CinemaScreen s : screens) {
            String owner = ScreenAdminClient.ownerOf(s.id());
            if (owner != null && !owner.isEmpty()) return owner;
        }
        return "未知玩家";
    }

    /** 一组屏幕的队列条目总数。 */
    private static int queueCountOf(List<CinemaScreen> screens) {
        int n = 0;
        for (CinemaScreen s : screens) {
            n += QueueClient.countFor(s.id());
        }
        return n;
    }

    /** 单屏明细页的目标屏幕。 */
    private CinemaScreen targetScreen() {
        return screenId == null ? null : ClientScreenManager.get().getScreen(screenId);
    }

    // ───────────── 队列操作（与原来一致：乐观本地更新 + 服务端执行 + 两次确认） ─────────────

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
        }
        if (!globalScope && QueueClient.countFor(sid) == 0
                && sid.toString().equals(pendingClearScreenId)) {
            pendingClearScreenId = null;   // 队列已空：清空确认状态复位
        }
        int maxPage = Math.max(0, (buildRows().size() - 1) / ROWS_PER_PAGE);
        page = Math.min(page, maxPage);
        rebuildWidgets();
    }

    /** 点全局专用队列条目：两次点击确认后向所有屏幕的 owner 发播放申请（服务端执行）。 */
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

    /** 清空全局专用队列：两次点击确认。 */
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
        if (view == View.SCREEN_DETAIL) {
            page = 0;   // 单屏明细页原行为：清空后回到第一页
        } else {
            // 屏幕列表页：清空某屏队列后行数变少，当前页跟随收敛
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
