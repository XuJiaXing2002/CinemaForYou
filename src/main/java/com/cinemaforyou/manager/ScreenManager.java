package com.cinemaforyou.manager;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.config.ServerConfig;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenOrientation;
import com.cinemaforyou.data.ScreenState;
import com.cinemaforyou.network.NetworkHandlers;
import com.cinemaforyou.network.QueueEntry;
import com.cinemaforyou.network.ScreenStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端屏幕管理器：持有所有屏幕定义 + 运行时状态，并负责持久化与广播。
 *
 * <p>线程模型：所有公开方法均在服务端主线程调用（由命令、网络包或 tick 调用）。
 */
public class ScreenManager {

    /** 最大屏幕尺寸（块²），防止单屏过大压垮客户端。 */
    public static final int MAX_AREA = 32 * 32; // 1024 块²

    /** 播放申请有效期（毫秒）：超时后不可再接受，发起者回执计入"超时未响应"。 */
    public static final long PLAY_REQUEST_TIMEOUT_MS = 60_000L;

    private static final Logger LOGGER = CinemaForYou.LOGGER;

    private final MinecraftServer server;
    private final Map<UUID, CinemaScreen> screens = new HashMap<>();
    private final Map<UUID, RuntimeState> runtime = new HashMap<>();
    /** 屏幕描述（仅创建者可改，持久化于 screens.dat）。 */
    private final Map<UUID, String> screenDescs = new HashMap<>();
    /** 屏幕创建者显示名缓存（在线时实时解析，离线用此兜底）。 */
    private final Map<UUID, String> screenOwnerNames = new HashMap<>();
    /** 服务器媒体文件归属（首次播放者）。 */
    private final Map<String, String> mediaOwners = new HashMap<>();
    /** 服务器播放历史（谁在何时播放了什么，最多 300 条）。 */
    private final List<LogEntry> playLog = new ArrayList<>();
    /** 每屏播放队列（服务端唯一数据源）：屏幕 UUID → 条目（URL + 加入者玩家名 + 加入时间）。 */
    private final Map<UUID, List<QueueEntry>> queues = new HashMap<>();
    /**
     * 全局播放队列（总设置入口，服务端唯一数据源）。
     *
     * <p>与每屏队列语义不同：条目不参与任何屏幕的"自动播放下一个"，
     * 只在「全局播放队列管理」界面手动点击时才向所有屏幕的 owner 发播放申请。
     */
    private final List<QueueEntry> globalQueue = new ArrayList<>();
    /** 待处理的播放申请（requestId → 申请分项）；接受/拒绝/超时后移除，requestId 唯一防串号。 */
    private final Map<UUID, PlayRequestItem> pendingPlayRequests = new HashMap<>();

    /** 一条播放记录。 */
    public static final class LogEntry {
        public final UUID id;
        public final long timeMs;
        public final String playerUuid;
        public final String playerName;
        public final String url;

        public LogEntry(UUID id, long timeMs, String playerUuid, String playerName, String url) {
            this.id = id;
            this.timeMs = timeMs;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
            this.url = url;
        }
    }

    /** 播放申请状态（PENDING 才可接受/拒绝）。 */
    private enum PlayRequestStatus { PENDING, ACCEPTED, DENIED, OFFLINE, EXPIRED }

    /** 一次全局播放申请批次（发起者 + 视频 + 各屏申请分项）。 */
    private static final class PlayRequestBatch {
        final UUID initiatorId;
        final String initiatorName;
        final String url;
        final long sentAtMs = System.currentTimeMillis();
        final List<PlayRequestItem> items = new ArrayList<>();
        /** 还没出结果（仍 PENDING）的分项数。 */
        int pending = 0;
        boolean summarySent = false;

        PlayRequestBatch(UUID initiatorId, String initiatorName, String url) {
            this.initiatorId = initiatorId;
            this.initiatorName = initiatorName;
            this.url = url;
        }
    }

    /** 批次中的单屏申请分项（requestId 唯一，聊天栏接受/拒绝时据此定位）。 */
    private static final class PlayRequestItem {
        final UUID requestId = UUID.randomUUID();
        final PlayRequestBatch batch;
        final UUID screenId;
        final String screenName;
        final String screenWhere;
        final String ownerId;
        final String ownerName;
        PlayRequestStatus status = PlayRequestStatus.PENDING;

        PlayRequestItem(PlayRequestBatch batch, UUID screenId, String screenName,
                        String screenWhere, String ownerId, String ownerName) {
            this.batch = batch;
            this.screenId = screenId;
            this.screenName = screenName;
            this.screenWhere = screenWhere;
            this.ownerId = ownerId == null ? "" : ownerId;
            this.ownerName = ownerName;
        }
    }

    public ScreenManager(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public int size() {
        return screens.size();
    }

    public List<CinemaScreen> allScreens() {
        return new ArrayList<>(screens.values());
    }

    public CinemaScreen get(UUID id) {
        return screens.get(id);
    }

    /** 找出指定玩家最近创建的屏幕（用于 CreateScreenPayload 后立即设置 URL）。 */
    public CinemaScreen findRecentByOwner(ServerPlayer player) {
        CinemaScreen recent = null;
        long maxTime = 0;
        String uuid = player.getUUID().toString();
        for (CinemaScreen s : screens.values()) {
            if (s.ownerId().equals(uuid) && s.createdAt() > maxTime) {
                maxTime = s.createdAt();
                recent = s;
            }
        }
        return recent;
    }

    // ───────────── 创建 / 删除 ─────────────

    /**
     * 创建屏幕。
     *
     * @param customId 可选自定义短名称；null/空白 = 自动 UUID，仅可用 UUID 引用
     * @return 创建的屏幕；校验失败返回 null
     */
    public CinemaScreen create(BlockPos c1, BlockPos c2, ServerPlayer owner, String customId) {
        ServerConfig cfg = CinemaForYou.serverConfig;
        int maxArea = (cfg != null) ? cfg.maxScreenArea : MAX_AREA;

        // 自定义 ID 校验
        String cid = normalizeCustomId(customId);
        if (customId != null && !customId.isBlank() && cid.isEmpty()) {
            owner.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 自定义 ID 无效：需 1-32 个字符且不含空格"));
            return null;
        }
        if (!cid.isEmpty() && findByCustomId(cid) != null) {
            owner.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 自定义 ID §e" + cid + "§c 已被占用"));
            return null;
        }

        ScreenOrientation orient = ScreenOrientation.fromCorners(c1, c2);
        CinemaScreen screen = new CinemaScreen(
                UUID.randomUUID(),
                c1.immutable(),
                c2.immutable(),
                orient,
                "", // 初始无源
                owner.getUUID().toString(),
                System.currentTimeMillis(),
                cid,
                100,
                100,
                720,
                100
        );
        if (screen.area() > maxArea) {
            owner.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 屏幕面积 " + screen.area() + " 超过上限 " + maxArea));
            return null;
        }
        // 每玩家屏幕数限制
        if (cfg != null && cfg.maxScreensPerPlayer > 0) {
            long count = screens.values().stream()
                    .filter(s -> s.ownerId().equals(owner.getUUID().toString()))
                    .count();
            if (count >= cfg.maxScreensPerPlayer) {
                owner.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 你已达到屏幕数量上限 " + cfg.maxScreensPerPlayer));
                return null;
            }
        }
        screens.put(screen.id(), screen);
        runtime.put(screen.id(), new RuntimeState());
        screenOwnerNames.put(screen.id(), playerDisplayName(owner));
        save();
        NetworkHandlers.broadcastSync(allScreens());
        owner.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 屏幕已创建，ID: " + screen.displayName()));
        return screen;
    }

    /** 玩家显示名（优先记分板名，兜底 UUID 前 8 位）。 */
    private static String playerDisplayName(ServerPlayer player) {
        try {
            String n = player.getScoreboardName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        return player.getUUID().toString().substring(0, 8);
    }

    /** 屏幕元数据行（创建者名 + 描述），供屏幕管理列表。 */
    public List<com.cinemaforyou.network.ScreenMetaPayload.Entry> adminMeta() {
        List<com.cinemaforyou.network.ScreenMetaPayload.Entry> out = new ArrayList<>();
        for (CinemaScreen s : screens.values()) {
            out.add(new com.cinemaforyou.network.ScreenMetaPayload.Entry(
                    s.id(),
                    resolveOwnerName(s),
                    screenDescs.getOrDefault(s.id(), "")));
        }
        return out;
    }

    /** 解析创建者名：在线优先，其次缓存，最后 UUID 短串。 */
    private String resolveOwnerName(CinemaScreen s) {
        if (server != null) {
            ServerPlayer p = server.getPlayerList().getPlayer(
                    java.util.UUID.fromString(s.ownerId()));
            if (p != null) {
                String n = playerDisplayName(p);
                screenOwnerNames.put(s.id(), n);
                return n;
            }
        }
        return screenOwnerNames.getOrDefault(s.id(), s.ownerId().substring(0, 8));
    }

    /** 改名（仅创建者）。 */
    public void renameScreen(UUID id, String newCustomId, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!s.ownerId().equals(requester.getUUID().toString())) {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 只有屏幕创建者可以改名"));
            return;
        }
        String cid = normalizeCustomId(newCustomId);
        if (!cid.isEmpty() && findByCustomId(cid) != null) {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 名称 §e" + cid + "§c 已被其它屏幕占用"));
            return;
        }
        screens.put(id, s.withCustomId(cid));
        save();
        NetworkHandlers.broadcastSync(allScreens());
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 屏幕已改名: " + (cid.isEmpty() ? "（未命名）" : cid)));
    }

    /** 设置描述（仅创建者；允许为空）。 */
    public void setScreenDesc(UUID id, String desc, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!s.ownerId().equals(requester.getUUID().toString())) {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 只有屏幕创建者可以修改描述"));
            return;
        }
        String clean = desc == null ? "" : desc.trim();
        if (clean.length() > 200) clean = clean.substring(0, 200);
        if (clean.isEmpty()) {
            screenDescs.remove(id);
        } else {
            screenDescs.put(id, clean);
        }
        save();
        requester.sendSystemMessage(Component.literal("§a[CinemaForYou] 描述已更新"));
    }

    /** 删除单个屏幕（仅创建者）。 */
    public void deleteOwned(UUID id, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!s.ownerId().equals(requester.getUUID().toString())) {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 只有屏幕创建者可以删除该屏幕"));
            return;
        }
        delete(id, requester);
    }

    /** 删除该玩家创建的全部屏幕。 */
    public void deleteAllOwned(ServerPlayer requester) {
        String uid = requester.getUUID().toString();
        int removed = 0;
        List<UUID> ids = new ArrayList<>();
        for (CinemaScreen s : screens.values()) {
            if (s.ownerId().equals(uid)) {
                ids.add(s.id());
            }
        }
        for (UUID id : ids) {
            CinemaScreen s = screens.remove(id);
            if (s != null) {
                runtime.remove(id);
                screenDescs.remove(id);
                queues.remove(id);
                removed++;
            }
        }
        if (removed > 0) {
            save();
            NetworkHandlers.broadcastSync(allScreens());
        }
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 已删除你创建的 " + removed + " 个屏幕"));
    }

    /** 兼容旧签名（无自定义 ID）。 */
    public CinemaScreen create(BlockPos c1, BlockPos c2, ServerPlayer owner) {
        return create(c1, c2, owner, null);
    }

    /** 规范化自定义 ID：trim、限长 32、禁止空白字符；无效返回空串。 */
    private static String normalizeCustomId(String input) {
        if (input == null) return "";
        String cid = input.trim();
        if (cid.isEmpty() || cid.length() > 32) return "";
        for (int i = 0; i < cid.length(); i++) {
            if (Character.isWhitespace(cid.charAt(i))) return "";
        }
        return cid;
    }

    /** 按自定义 ID 精确（忽略大小写）查找屏幕。 */
    private CinemaScreen findByCustomId(String cid) {
        for (CinemaScreen s : screens.values()) {
            if (s.customId() != null && s.customId().equalsIgnoreCase(cid)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 将用户输入解析为屏幕：自定义 ID 精确匹配 → UUID 精确匹配 → UUID 前 8 位前缀匹配。
     *
     * @return 屏幕定义，未找到返回 null
     */
    public CinemaScreen resolve(String input) {
        if (input == null || input.isBlank()) return null;
        CinemaScreen byCid = findByCustomId(input.trim());
        if (byCid != null) return byCid;
        String lower = input.trim().toLowerCase();
        try {
            return screens.get(UUID.fromString(lower));
        } catch (IllegalArgumentException ignored) {}
        // UUID 前缀匹配
        for (CinemaScreen s : screens.values()) {
            if (s.id().toString().startsWith(lower)) return s;
        }
        return null;
    }

    /** 列出所有屏幕的可引用 ID（自定义名优先，否则完整 UUID），用于命令补全。 */
    public List<String> collectIdSuggestions() {
        List<String> out = new ArrayList<>();
        for (CinemaScreen s : screens.values()) {
            if (s.customId() != null && !s.customId().isEmpty()) {
                out.add(s.customId());
            } else {
                out.add(s.id().toString());
            }
        }
        return out;
    }

    public boolean delete(UUID id, ServerPlayer requester) {
        CinemaScreen s = screens.remove(id);
        if (s == null) {
            requester.sendSystemMessage(Component.literal("§c未找到屏幕 " + id));
            return false;
        }
        runtime.remove(id);
        screenDescs.remove(id);
        queues.remove(id);
        save();
        NetworkHandlers.broadcastSync(allScreens());
        broadcastQueues();
        requester.sendSystemMessage(Component.literal("§a已删除屏幕 " + id));
        return true;
    }

    // ───────────── 播放控制 ─────────────

    public void play(UUID id, String url, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        String effective = preparePlayUrl(url, List.of(id), requester);
        if (effective == null) return;   // 被拒绝或延后转封装
        boolean served = !effective.equals(url);
        applyPlay(id, effective);
        requester.sendSystemMessage(buildPlayMessage("§a▶ 播放: ", served ? effective : url));
        if (url.startsWith("file:") && !served) {
            requester.sendSystemMessage(Component.literal(
                    "§7提示：服务端 cinema/videos/ 下没有同名文件，只有本机客户端能看到该画面。"
                            + "想让所有人观看，请把视频放进服务器目录 cinema/videos/ 后重新播放。"));
        }
        recordPlay(requester, effective, url);
    }

    /**
     * 广播播放（总设置入口）：不直接播放，而是向每个屏幕的 owner 发送一条"播放申请"。
     *
     * <p>不再有整体忙碌拦截：无论各屏是否正在播放/队列是否非空，申请一律下发
     * （旧版"任一屏忙则整体拒绝"的逻辑已取消）。
     *
     * <p>语义：owner 在聊天栏点「接受」后才会在该屏播放（走 {@link #preparePlayUrl} +
     * {@link #applyPlay}，白名单/本地文件改写/大文件转封装延迟等既有机制原样生效）；
     * 点「拒绝」该屏不播；owner 不在线/无 owner 视为无法送达；
     * 申请 {@link #PLAY_REQUEST_TIMEOUT_MS} 毫秒内有效，超时不可再接受。
     * 发起者会收到逐条结果与最终汇总回执（接受谁/拒绝谁/离线谁/超时未响应谁）。
     *
     * <p>权限：广播到所有屏幕属于全局操作，沿用总设置的 OP（等级 ≥2）规则。
     */
    public void playAll(String url, ServerPlayer requester) {
        if (url == null || url.isEmpty()) return;
        if (!isOp(requester)) {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 只有管理员可以把视频播放到所有屏幕"));
            return;
        }
        if (screens.isEmpty()) {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 还没有可播放的屏幕（先用选择器创建）"));
            return;
        }
        // 白名单/本地文件校验：不合格就不发申请（接受时还会再校验一次，服务端权威不变）
        if (!checkPlayUrl(url, requester)) return;

        PlayRequestBatch batch = new PlayRequestBatch(
                requester.getUUID(), playerDisplayName(requester), url);
        for (CinemaScreen s : new ArrayList<>(screens.values())) {
            ServerPlayer owner = onlineOwner(s);
            PlayRequestItem item = new PlayRequestItem(batch, s.id(), s.displayName(),
                    s.center().toShortString(), s.ownerId(), resolveOwnerName(s));
            batch.items.add(item);
            if (owner == null) {
                // owner 不在线 / 无 owner：无法送达，只计入汇总回执
                item.status = PlayRequestStatus.OFFLINE;
                continue;
            }
            batch.pending++;
            pendingPlayRequests.put(item.requestId, item);
            sendPlayRequest(owner, item);
        }
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 已向 " + batch.items.size() + " 个屏幕发送播放申请"
                        + "（owner 接受后才播放；" + (PLAY_REQUEST_TIMEOUT_MS / 1000) + " 秒内有效）"));
        maybeSendSummary(batch);   // 全部离线/无人时直接给出汇总回执
        LOGGER.debug("[CinemaForYou] 播放申请已派发: 视频={} 屏幕={} 待响应={}",
                shortUrl(url), batch.items.size(), batch.pending);
    }

    /** 屏幕 owner 的在线玩家（owner 字段为空 / 玩家不在线返回 null）。 */
    private ServerPlayer onlineOwner(CinemaScreen s) {
        if (s.ownerId() == null || s.ownerId().isEmpty()) return null;
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(s.ownerId()));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 向屏幕 owner 发送聊天栏播放申请（发起者/视频/屏幕名与坐标 + 可点击「接受」「拒绝」）。 */
    private static void sendPlayRequest(ServerPlayer owner, PlayRequestItem item) {
        owner.sendSystemMessage(Component.literal(
                "§e[CinemaForYou] §f收到来自 §b" + item.batch.initiatorName + "§f 的播放申请："));
        owner.sendSystemMessage(Component.literal("§f  视频: ").append(videoLabel(item.batch.url)));
        MutableComponent buttons = Component.literal("§f  [");
        buttons.append(Component.literal("§a✔ 接受").withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/cinema accept " + item.requestId))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                        "§a同意在该屏幕播放该视频")))));
        buttons.append(Component.literal("§f] ["));
        buttons.append(Component.literal("§c✘ 拒绝").withStyle(Style.EMPTY
                .withClickEvent(new ClickEvent.RunCommand("/cinema deny " + item.requestId))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("§c拒绝该播放申请")))));
        buttons.append(Component.literal("§f]  §7（" + (PLAY_REQUEST_TIMEOUT_MS / 1000) + " 秒内有效）"));
        owner.sendSystemMessage(buttons);
    }

    /**
     * 处理屏幕 owner 对播放申请的应答（聊天栏「接受/拒绝」→ /cinema accept|deny &lt;requestId&gt;）。
     *
     * @return null = 已处理；否则为给应答者的错误提示
     */
    public String respondPlayRequest(ServerPlayer responder, UUID requestId, boolean accept) {
        if (requestId == null) return "§c[CinemaForYou] 播放申请 ID 无效";
        PlayRequestItem item = pendingPlayRequests.get(requestId);
        if (item == null) {
            return "§c[CinemaForYou] 该播放申请不存在或已处理";
        }
        if (System.currentTimeMillis() - item.batch.sentAtMs >= PLAY_REQUEST_TIMEOUT_MS) {
            expireItem(item);
            notifyInitiator(item, "§7[CinemaForYou] 屏幕「" + item.screenName + "」的 owner「"
                    + item.ownerName + "」超时未响应，申请已过期");
            maybeSendSummary(item.batch);
            return "§c[CinemaForYou] 该播放申请已过期（超过 "
                    + (PLAY_REQUEST_TIMEOUT_MS / 1000) + " 秒）";
        }
        if (!item.ownerId.equals(responder.getUUID().toString())) {
            return "§c[CinemaForYou] 只有该屏幕的 owner 可以处理此播放申请";
        }
        item.status = accept ? PlayRequestStatus.ACCEPTED : PlayRequestStatus.DENIED;
        item.batch.pending--;
        pendingPlayRequests.remove(requestId);
        if (accept) {
            // 接受：沿用既有播放流程（白名单/本地文件改写/大文件转封装延迟都在 preparePlayUrl 内）
            play(item.screenId, item.batch.url, responder);
            responder.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] 已接受播放申请，屏幕「" + item.screenName + "」开始播放"));
            notifyInitiator(item, "§a[CinemaForYou] 屏幕「" + item.screenName + "」的 owner「"
                    + item.ownerName + "」已接受，开始播放");
        } else {
            responder.sendSystemMessage(Component.literal("§e[CinemaForYou] 已拒绝该播放申请"));
            notifyInitiator(item, "§c[CinemaForYou] 屏幕「" + item.screenName + "」的 owner「"
                    + item.ownerName + "」已拒绝");
        }
        maybeSendSummary(item.batch);
        return null;
    }

    /** 把待处理申请标记为超时（幂等）。 */
    private void expireItem(PlayRequestItem item) {
        if (item.status != PlayRequestStatus.PENDING) return;
        item.status = PlayRequestStatus.EXPIRED;
        item.batch.pending--;
        pendingPlayRequests.remove(item.requestId);
    }

    /** 给发起者发一条回执（不在线则跳过，回执不落盘）。 */
    private void notifyInitiator(PlayRequestItem item, String message) {
        ServerPlayer initiator = server.getPlayerList().getPlayer(item.batch.initiatorId);
        if (initiator != null) {
            initiator.sendSystemMessage(Component.literal(message));
        }
    }

    /** 批次内全部申请都有结果/超时后，给发起者发汇总回执（接受谁/拒绝谁/离线谁/超时未响应谁）。 */
    private void maybeSendSummary(PlayRequestBatch batch) {
        if (batch.summarySent || batch.pending > 0) return;
        batch.summarySent = true;
        ServerPlayer initiator = server.getPlayerList().getPlayer(batch.initiatorId);
        if (initiator == null) return;
        StringBuilder accepted = new StringBuilder();
        StringBuilder denied = new StringBuilder();
        StringBuilder offline = new StringBuilder();
        StringBuilder expired = new StringBuilder();
        for (PlayRequestItem item : batch.items) {
            StringBuilder target = switch (item.status) {
                case ACCEPTED -> accepted;
                case DENIED -> denied;
                case OFFLINE -> offline;
                case EXPIRED -> expired;
                default -> null;
            };
            if (target == null) continue;   // PENDING：不会发生（pending==0 才发汇总）
            if (!target.isEmpty()) target.append("、");
            target.append("「").append(item.screenName).append("」");
            if (item.ownerId.isEmpty()) {
                target.append("（无 owner）");
            } else {
                target.append("（owner ").append(item.ownerName).append("）");
            }
        }
        initiator.sendSystemMessage(Component.literal(
                "§e[CinemaForYou] 播放申请汇总（视频: §f" + shortUrl(batch.url) + "§e）："
                        + "接受 " + countStatus(batch, PlayRequestStatus.ACCEPTED)
                        + "，拒绝 " + countStatus(batch, PlayRequestStatus.DENIED)
                        + "，离线/无人 " + countStatus(batch, PlayRequestStatus.OFFLINE)
                        + "，超时未响应 " + countStatus(batch, PlayRequestStatus.EXPIRED)));
        if (!accepted.isEmpty()) {
            initiator.sendSystemMessage(Component.literal("§a  接受: " + accepted));
        }
        if (!denied.isEmpty()) {
            initiator.sendSystemMessage(Component.literal("§c  拒绝: " + denied));
        }
        if (!offline.isEmpty()) {
            initiator.sendSystemMessage(Component.literal("§7  离线/无人: " + offline));
        }
        if (!expired.isEmpty()) {
            initiator.sendSystemMessage(Component.literal("§7  超时未响应: " + expired));
        }
    }

    private static int countStatus(PlayRequestBatch batch, PlayRequestStatus status) {
        int n = 0;
        for (PlayRequestItem item : batch.items) {
            if (item.status == status) n++;
        }
        return n;
    }

    /** 申请里的视频显示（file: 只显示文件名；http 链接可点击打开）。 */
    private static MutableComponent videoLabel(String url) {
        String shown = shortUrl(url);
        if (shown.startsWith("http://") || shown.startsWith("https://")) {
            return buildPlayMessage("", shown);
        }
        return Component.literal("§a" + shown);
    }

    /**
     * 播放入口的 URL 校验（本地文件开关 + 域名白名单）；失败时提示并返回 false。
     *
     * <p>播放入口（{@link #play}、{@link #playAll} 发申请前）共用，保持服务端权威。
     */
    private static boolean checkPlayUrl(String url, ServerPlayer requester) {
        ServerConfig cfg = CinemaForYou.serverConfig;
        if (cfg != null) {
            if (!cfg.isLocalFileAllowed(url)) {
                requester.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 服务端已禁止播放本地文件"));
                return false;
            }
            if (!cfg.isUrlAllowed(url)) {
                requester.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 此视频域名不在白名单中"));
                return false;
            }
        }
        return true;
    }

    /**
     * 播放前处理（所有播放入口共用）：配置校验（本地文件/域名白名单）、
     * 服务端大文件自动转封装延迟、本地文件改写成服务端媒体地址。
     *
     * @param screenIds 本次播放涉及的屏幕（转封装完成后逐屏自动开播；PLAY_ALL 传全体）
     * @return 实际应下发的 URL；被拒绝或已延后转封装时返回 null
     */
    private String preparePlayUrl(String url, List<UUID> screenIds, ServerPlayer requester) {
        // 配置校验：本地文件与域名白名单
        if (!checkPlayUrl(url, requester)) return null;
        // 服务端媒体大文件自动转封装：命中需优化的容器时先排队转封装，
        // 完成后自动开始播放（避免远程玩家播放时的探测跳读网络开销）
        if (url.startsWith("file:")) {
            String fileName = new java.io.File(url.substring("file:".length())).getName();
            if (MediaRemuxer.maybeDeferPlay(screenIds, requester, fileName)) {
                return null;
            }
        }
        // 本地文件优先改写成服务端媒体地址（文件须在 服务器目录/cinema/videos/ 下，
        // 这样服务器上其它玩家也能拉流观看）；改不了才保持原样（仅本机可见）。
        if (url.startsWith("file:")) {
            return com.cinemaforyou.manager.MediaHttpServer.mapLocalFileToHttp(url);
        }
        return url;
    }

    /** 真正下发播放：更新屏幕 sourceUrl 与运行时状态并广播（不校验、不提示）。 */
    private void applyPlay(UUID id, String effective) {
        CinemaScreen s = screens.get(id);
        if (s == null) return;
        // 更新屏幕定义中的 sourceUrl（记录最后播放的 URL，供重播使用）
        if (!effective.isEmpty() && !effective.equals(s.sourceUrl())) {
            screens.put(id, s.withSourceUrl(effective));
            save();
            NetworkHandlers.broadcastSync(allScreens());
        }
        RuntimeState rt = runtime.computeIfAbsent(id, k -> new RuntimeState());
        rt.state = ScreenState.PLAYING;
        rt.sourceUrl = effective;
        rt.positionMs = 0;
        rt.lastServerTime = System.currentTimeMillis();
        rt.dirty = true;
        broadcastState(id);
    }

    /**
     * 播放开始聊天消息：完整显示链接（不截断），链接部分可点击跳转浏览器。
     */
    private static MutableComponent buildPlayMessage(String prefix, String url) {
        String shown = (url == null || url.isEmpty()) ? "<空>" : url;
        MutableComponent msg = Component.literal(prefix);
        if (shown.startsWith("http://") || shown.startsWith("https://")) {
            try {
                Style link = Style.EMPTY
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create(shown)));
                msg.append(Component.literal(shown).withStyle(link));
                return msg;
            } catch (Exception ignored) {
                // URI 非法则退回纯文本
            }
        }
        msg.append(Component.literal(shown));
        return msg;
    }

    /** 记录一次播放（服务器播放历史），并顺带登记服务器媒体文件的首个播放者。 */
    private void recordPlay(ServerPlayer requester, String effectiveUrl, String rawUrl) {
        try {
            if (effectiveUrl == null || effectiveUrl.isEmpty()) return;
            if (playLog.size() >= 300) {
                playLog.remove(0);
            }
            playLog.add(new LogEntry(UUID.randomUUID(), System.currentTimeMillis(),
                    requester.getUUID().toString(), playerDisplayName(requester), effectiveUrl));
            // 媒体文件归属：首次被播放时记录是谁"添加"进来的
            if (rawUrl != null && rawUrl.startsWith("file:")) {
                String name = new java.io.File(rawUrl.substring("file:".length())).getName();
                if (!name.isEmpty() && !mediaOwners.containsKey(name)
                        && new java.io.File(MediaHttpServer.mediaDirectory(), name).isFile()) {
                    mediaOwners.put(name, playerDisplayName(requester));
                }
            }
            save();
        } catch (Throwable t) {
            LOGGER.warn("[CinemaForYou] 播放历史记录失败: {}", t.toString());
        }
    }

    /** 播放历史（最新在前）。 */
    public List<LogEntry> playLogEntries() {
        List<LogEntry> copy = new ArrayList<>(playLog);
        copy.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));
        return copy;
    }

    /** 删除一条播放记录（仅本人）。 */
    public void deleteLogEntry(UUID entryId, ServerPlayer requester) {
        String uid = requester.getUUID().toString();
        boolean removed = playLog.removeIf(e -> e.id.equals(entryId) && e.playerUuid.equals(uid));
        if (removed) {
            save();
            requester.sendSystemMessage(Component.literal("§a[CinemaForYou] 已删除该条历史"));
        } else {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 只能删除自己产生的播放历史"));
        }
    }

    /** 清空本人全部播放历史。 */
    public void clearMyLog(ServerPlayer requester) {
        String uid = requester.getUUID().toString();
        int before = playLog.size();
        playLog.removeIf(e -> e.playerUuid.equals(uid));
        int removed = before - playLog.size();
        if (removed > 0) {
            save();
        }
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 已清空你自己的 " + removed + " 条播放历史"));
    }

    /** 某媒体文件的添加者（未知返回空串）。 */
    public String mediaOwnerOf(String name) {
        String o = mediaOwners.get(name);
        return o == null ? "" : o;
    }

    /** 媒体文件被删除后清理归属记录。 */
    public void forgetMedia(String name) {
        if (mediaOwners.remove(name) != null) {
            save();
        }
    }

    // ───────────── 播放队列（服务端唯一数据源） ─────────────

    /** 是否 OP（权限等级 ≥2），与各"仅管理员"入口的判定一致。 */
    private static boolean isOp(ServerPlayer player) {
        return player.createCommandSourceStack().permissions().hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    /** 判断玩家是否有权控制某屏幕：owner 或 op 等级 ≥2（与网络层校验一致）。 */
    public static boolean canControl(CinemaScreen screen, ServerPlayer player) {
        if (screen.ownerId().equals(player.getUUID().toString())) {
            return true;
        }
        return isOp(player);
    }

    /** 无权限统一提示。 */
    private static void deny(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(
                "§c[CinemaForYou] 你没有控制此屏幕的权限（仅 owner 或管理员）"));
    }

    /** 某屏队列的只读副本（不存在返回空列表）。 */
    public List<QueueEntry> queueOf(UUID id) {
        List<QueueEntry> q = queues.get(id);
        return q == null ? List.of() : List.copyOf(q);
    }

    /** 全部屏幕的队列条目 + 全局播放队列条目（供 S2C 全量同步，客户端按 global 标志区分）。 */
    public List<QueueEntry> allQueueEntries() {
        List<QueueEntry> out = new ArrayList<>();
        for (List<QueueEntry> q : queues.values()) {
            out.addAll(q);
        }
        out.addAll(globalQueue);
        return out;
    }

    /** 队列是否已包含该 URL（去重用）。 */
    private static boolean containsUrl(List<QueueEntry> q, String url) {
        for (QueueEntry e : q) {
            if (e.url().equals(url)) return true;
        }
        return false;
    }

    /** 入队（同屏同 URL 去重）；加入者与加入时间由服务端记录。 */
    public void queueAdd(UUID id, String url, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!canControl(s, requester)) { deny(requester); return; }
        if (url == null || url.isEmpty()) return;
        List<QueueEntry> q = queues.computeIfAbsent(id, k -> new ArrayList<>());
        if (containsUrl(q, url)) {
            requester.sendSystemMessage(Component.literal(
                    "§7[CinemaForYou] 队列中已有该项"));
            return;
        }
        q.add(new QueueEntry(id, url, playerDisplayName(requester), System.currentTimeMillis()));
        save();
        broadcastQueues();
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 已加入队列: " + shortUrl(url)
                        + " §7（播完模式选「自动播放下一个」生效）"));
    }

    // ───────────── 全局播放队列（总设置入口，不参与自动连播） ─────────────

    /** 全局播放队列只读副本。 */
    public List<QueueEntry> globalQueueEntries() {
        return List.copyOf(globalQueue);
    }

    /** 全局队列入口只对 OP（等级 ≥2）开放，与总设置的其它管理入口一致。 */
    private static boolean requireOp(ServerPlayer player) {
        if (isOp(player)) return true;
        player.sendSystemMessage(Component.literal(
                "§c[CinemaForYou] 只有管理员可以管理全局播放队列"));
        return false;
    }

    /**
     * 总设置入口：把 URL 加入全局播放队列（同 URL 去重）。
     *
     * <p>全局队列条目不参与任何屏幕的"自动播放下一个"，只在「全局播放队列管理」里
     * 手动点击时才向所有屏幕的 owner 发播放申请。
     */
    public void globalQueueAdd(String url, ServerPlayer requester) {
        if (!requireOp(requester)) return;
        if (url == null || url.isEmpty()) return;
        if (containsUrl(globalQueue, url)) {
            requester.sendSystemMessage(Component.literal(
                    "§7[CinemaForYou] 全局播放队列中已有该项"));
            return;
        }
        globalQueue.add(new QueueEntry(null, url, playerDisplayName(requester),
                System.currentTimeMillis(), true));
        save();
        broadcastQueues();
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 已加入全局播放队列: " + shortUrl(url)
                        + " §7（不参与自动连播；在「全局播放队列管理」里手动点击才会向所有屏幕发送播放申请）"));
    }

    /** 删除全局播放队列中的一项。 */
    public void globalQueueRemove(int index, ServerPlayer requester) {
        if (!requireOp(requester)) return;
        if (index < 0 || index >= globalQueue.size()) return;
        globalQueue.remove(index);
        save();
        broadcastQueues();
    }

    /** 清空全局播放队列。 */
    public void globalQueueClear(ServerPlayer requester) {
        if (!requireOp(requester)) return;
        if (globalQueue.isEmpty()) return;
        globalQueue.clear();
        save();
        broadcastQueues();
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 已清空全局播放队列"));
    }

    /** 全局队列上移/下移（仅影响手动点击的顺序，不参与自动连播）。 */
    public void globalQueueMove(int from, int to, ServerPlayer requester) {
        if (!requireOp(requester)) return;
        if (from < 0 || from >= globalQueue.size() || to < 0 || to >= globalQueue.size() || from == to) {
            return;
        }
        QueueEntry item = globalQueue.remove(from);
        globalQueue.add(to, item);
        save();
        broadcastQueues();
    }

    /** 手动点击全局队列第 index 项：向所有屏幕的 owner 发播放申请（与总设置播放入口同一路径）。 */
    public void globalQueuePlay(int index, ServerPlayer requester) {
        if (!requireOp(requester)) return;
        if (index < 0 || index >= globalQueue.size()) return;
        playAll(globalQueue.get(index).url(), requester);
    }

    /** 删除某屏队列中的一项。 */
    public void queueRemove(UUID id, int index, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!canControl(s, requester)) { deny(requester); return; }
        List<QueueEntry> q = queues.get(id);
        if (q == null || index < 0 || index >= q.size()) return;
        q.remove(index);
        if (q.isEmpty()) {
            queues.remove(id);
        }
        save();
        broadcastQueues();
    }

    /** 清空某屏队列。 */
    public void queueClear(UUID id, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!canControl(s, requester)) { deny(requester); return; }
        if (queues.remove(id) != null) {
            save();
            broadcastQueues();
            requester.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] 已清空该屏播放队列"));
        }
    }

    /** 上移/下移某屏队列中的一项（顺序决定"自动播放下一个"的次序）。 */
    public void queueMove(UUID id, int from, int to, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!canControl(s, requester)) { deny(requester); return; }
        List<QueueEntry> q = queues.get(id);
        if (q == null || from < 0 || from >= q.size() || to < 0 || to >= q.size() || from == to) {
            return;
        }
        QueueEntry item = q.remove(from);
        q.add(to, item);
        save();
        broadcastQueues();
    }

    /** 从队列立即播放第 index 项（条目保留在队列中，供"循环/自动下一个"继续使用）。 */
    public void queuePlay(UUID id, int index, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (!canControl(s, requester)) { deny(requester); return; }
        List<QueueEntry> q = queues.get(id);
        if (q == null || index < 0 || index >= q.size()) return;
        play(id, q.get(index).url(), requester);
    }

    /** 旧版客户端本机队列迁移上传：按权限过滤、同屏同 URL 去重后并入。 */
    public void queueUpload(ServerPlayer requester, List<QueueEntry> uploaded) {
        if (uploaded == null || uploaded.isEmpty()) return;
        int added = 0, dup = 0, denied = 0, missing = 0;
        for (QueueEntry e : uploaded) {
            if (e.screenId() == null || e.url().isEmpty()) continue;
            CinemaScreen s = screens.get(e.screenId());
            if (s == null) { missing++; continue; }
            if (!canControl(s, requester)) { denied++; continue; }
            List<QueueEntry> q = queues.computeIfAbsent(s.id(), k -> new ArrayList<>());
            if (containsUrl(q, e.url())) { dup++; continue; }
            q.add(new QueueEntry(s.id(), e.url(),
                    e.player().isEmpty() ? playerDisplayName(requester) : e.player(),
                    e.timeMs() > 0 ? e.timeMs() : System.currentTimeMillis()));
            added++;
        }
        if (added > 0) {
            save();
            broadcastQueues();
        }
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 本机队列迁移完成：新增 " + added + " 项"
                        + (dup > 0 ? "，已存在 " + dup + " 项" : "")
                        + (denied > 0 ? "，无权限 " + denied + " 项" : "")
                        + (missing > 0 ? "，屏幕已不存在 " + missing + " 项" : "")));
    }

    /** 广播全部屏幕队列（队列变更后调用）。 */
    private void broadcastQueues() {
        NetworkHandlers.broadcastQueues(allQueueEntries());
    }

    /** 队列条目显示用短名（本地文件取文件名，其余截断）。 */
    private static String shortUrl(String url) {
        if (url.startsWith("file:")) {
            String name = new java.io.File(url.substring("file:".length())).getName();
            if (!name.isEmpty()) return name;
        }
        return url.length() <= 80 ? url : url.substring(0, 77) + "...";
    }

    public void pause(UUID id, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        RuntimeState rt = runtime.get(id);
        if (rt == null || rt.state != ScreenState.PLAYING) {
            requester.sendSystemMessage(Component.literal("§c屏幕未在播放"));
            return;
        }
        rt.state = ScreenState.PAUSED;
        rt.dirty = true;
        broadcastState(id);
        requester.sendSystemMessage(Component.literal("§e⏸ 暂停"));
    }

    public void resume(UUID id, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        RuntimeState rt = runtime.get(id);
        if (rt == null || rt.state != ScreenState.PAUSED) {
            requester.sendSystemMessage(Component.literal("§c屏幕未暂停"));
            return;
        }
        rt.state = ScreenState.PLAYING;
        rt.lastServerTime = System.currentTimeMillis();
        rt.dirty = true;
        broadcastState(id);
        requester.sendSystemMessage(Component.literal("§a▶ 恢复播放"));
    }

    public void stop(UUID id, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        RuntimeState rt = runtime.get(id);
        if (rt == null) return;
        rt.state = ScreenState.STOPPED;
        rt.sourceUrl = "";
        rt.positionMs = 0;
        rt.dirty = true;
        broadcastState(id);
        requester.sendSystemMessage(Component.literal("§e⏹ 停止"));
    }

    /** 屏幕是否存在且未停止（自动转封装完成后据此决定是否自动开播）。 */
    boolean isScreenActive(UUID id) {
        if (!screens.containsKey(id)) return false;
        RuntimeState rt = runtime.get(id);
        return rt != null && rt.state.isActive();
    }

    public void seek(UUID id, long positionMs, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        if (positionMs < 0) positionMs = 0;
        RuntimeState rt = runtime.get(id);
        if (rt == null) return;
        rt.positionMs = positionMs;
        rt.lastServerTime = System.currentTimeMillis();
        rt.dirty = true;
        broadcastState(id);
        requester.sendSystemMessage(Component.literal(
                "§e⏩ 跳转到 " + (positionMs / 1000) + "s"));
    }

    /** 更新屏幕设置并广播到客户端。 */
    public void updateSettings(UUID id, int brightnessPercent, int volumePercent,
                               int resolutionHeight, int displayScalePercent,
                               int audioRangeBlocks, int audioFalloffTenths,
                               int curvatureType, int curvDegL, int curvDegR,
                               int curvDegT, int curvDegB,
                               int tiltDegH, int tiltDegV,
                               ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        CinemaScreen updated = s.withSettings(
                brightnessPercent, volumePercent, resolutionHeight, displayScalePercent)
                .withAudioSettings(audioRangeBlocks, audioFalloffTenths)
                .withCurvatureSettings(curvatureType, curvDegL, curvDegR, curvDegT, curvDegB)
                .withTiltSettings(tiltDegH, tiltDegV);
        screens.put(id, updated);
        save();
        NetworkHandlers.broadcastSync(allScreens());
        requester.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 已更新屏幕设置：亮度 " + updated.brightnessPercent()
                        + "%，音量 " + updated.volumePercent()
                        + "%，分辨率 " + updated.resolutionHeight()
                        + "p，大小 " + updated.displayScalePercent()
                        + "%，音频范围 " + (updated.audioRangeBlocks() > 0
                        ? updated.audioRangeBlocks() + " 格" : "默认")
                        + "，衰减 " + (updated.audioFalloffTenths() > 0
                        ? (updated.audioFalloffTenths() / 10.0) + "x" : "默认")
                        + "，曲面 " + curvatureLabel(updated.curvatureType(),
                        updated.curvDegL(), updated.curvDegR(),
                        updated.curvDegT(), updated.curvDegB())
                        + (updated.tiltDegH() != 0 || updated.tiltDegV() != 0
                        ? "，倾斜 " + updated.tiltDegH() + "/" + updated.tiltDegV() + "°" : "")));
    }

    /** 按世界坐标平移屏幕（上下左右移动），每次 ±1/±10 格。 */
    public void moveBy(UUID id, int dx, int dy, int dz, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        int cx = clampCoord(s.corner1().getX() + dx), cy = clampCoord(s.corner1().getY() + dy),
                cz = clampCoord(s.corner1().getZ() + dz);
        int cx2 = clampCoord(s.corner2().getX() + dx), cy2 = clampCoord(s.corner2().getY() + dy),
                cz2 = clampCoord(s.corner2().getZ() + dz);
        CinemaScreen moved = new CinemaScreen(
                s.id(),
                new BlockPos(cx, cy, cz),
                new BlockPos(cx2, cy2, cz2),
                s.orientation(), s.sourceUrl(), s.ownerId(), s.createdAt(), s.customId(),
                s.brightnessPercent(), s.volumePercent(), s.resolutionHeight(),
                s.displayScalePercent(), s.audioRangeBlocks(), s.audioFalloffTenths(),
                s.curvatureType(), s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB(),
                s.tiltDegH(), s.tiltDegV());
        screens.put(id, moved);
        save();
        NetworkHandlers.broadcastSync(allScreens());
        requester.sendSystemMessage(Component.literal(
                "§e[CinemaForYou] 屏幕已移动 (" + dx + "," + dy + "," + dz + ") 格"));
    }

    private static int clampCoord(int v) {
        return Math.max(-29999999, Math.min(29999999, v));
    }

    /** 单边拉缩屏幕：两个角点分别给世界偏移（由客户端按"哪条边"计算）。 */
    public void resizeBy(UUID id, int c1dx, int c1dy, int c1dz,
                         int c2dx, int c2dy, int c2dz, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        int x1 = clampCoord(s.corner1().getX() + c1dx);
        int y1 = clampCoord(s.corner1().getY() + c1dy);
        int z1 = clampCoord(s.corner1().getZ() + c1dz);
        int x2 = clampCoord(s.corner2().getX() + c2dx);
        int y2 = clampCoord(s.corner2().getY() + c2dy);
        int z2 = clampCoord(s.corner2().getZ() + c2dz);
        CinemaScreen resized = new CinemaScreen(
                s.id(),
                new BlockPos(x1, y1, z1),
                new BlockPos(x2, y2, z2),
                s.orientation(), s.sourceUrl(), s.ownerId(), s.createdAt(), s.customId(),
                s.brightnessPercent(), s.volumePercent(), s.resolutionHeight(),
                s.displayScalePercent(), s.audioRangeBlocks(), s.audioFalloffTenths(),
                s.curvatureType(), s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB(),
                s.tiltDegH(), s.tiltDegV());
        if (resized.width() < 1 || resized.height() < 1) {
            requester.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕至少要保持 1×1"));
            return;
        }
        int maxArea = (CinemaForYou.serverConfig != null)
                ? CinemaForYou.serverConfig.maxScreenArea : MAX_AREA;
        if (resized.area() > maxArea) {
            requester.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 拉缩后面积 " + resized.area() + " 超过上限 " + maxArea
                            + "（可在 config/cinemaforyou-server.json 调大 maxScreenArea）"));
            return;
        }
        screens.put(id, resized);
        save();
        NetworkHandlers.broadcastSync(allScreens());
        requester.sendSystemMessage(Component.literal(
                "§e[CinemaForYou] 屏幕边缘已拉缩"));
    }

    /**
     * 旧档迁移：老字段 curvX/curvY 是"水平/垂直总对称弧度"，四边模式下
     * 折算为左右各半（几何形状不变）；新字段存在时直接采用。上限 90°。
     */
    private static int migratedSide(CompoundTag tag, String newKey, String legacyKey, int legacyMax) {
        int v = tag.getIntOr(newKey, -1);
        if (v >= 0) return Math.max(0, Math.min(90, v));
        int legacy = Math.max(0, Math.min(legacyMax, tag.getIntOr(legacyKey, 0)));
        return Math.min(90, legacy / 2);
    }

    private static String curvatureLabel(int type, int l, int r, int t, int b) {
        if (type <= 0 || (l <= 0 && r <= 0 && t <= 0 && b <= 0)) return "平面";
        String name = switch (type) {
            case 1 -> "水平凸弧";
            case 2 -> "水平凹弧";
            case 3 -> "双向凸弧";
            case 4 -> "双向凹弧";
            default -> "平面";
        };
        StringBuilder sb = new StringBuilder(name);
        if (l > 0) sb.append(" 左").append(l).append("°");
        if (r > 0) sb.append(" 右").append(r).append("°");
        if (t > 0) sb.append(" 上").append(t).append("°");
        if (b > 0) sb.append(" 下").append(b).append("°");
        return sb.toString();
    }

    // ───────────── tick / 广播 ─────────────

    /** 每 tick 调用：推进 PLAYING 屏幕时钟；定期刷新状态用于客户端校准；播放申请超时清理。 */
    public void tick() {
        long now = System.currentTimeMillis();
        // 播放申请超时：过期后不可再接受，给发起者逐条回执并汇总
        if (!pendingPlayRequests.isEmpty()) {
            List<PlayRequestItem> expiredItems = null;
            for (PlayRequestItem item : new ArrayList<>(pendingPlayRequests.values())) {
                if (now - item.batch.sentAtMs >= PLAY_REQUEST_TIMEOUT_MS) {
                    if (expiredItems == null) expiredItems = new ArrayList<>();
                    expiredItems.add(item);
                }
            }
            if (expiredItems != null) {
                java.util.Set<PlayRequestBatch> affected = new java.util.HashSet<>();
                for (PlayRequestItem item : expiredItems) {
                    expireItem(item);
                    affected.add(item.batch);
                    notifyInitiator(item, "§7[CinemaForYou] 屏幕「" + item.screenName + "」的 owner「"
                            + item.ownerName + "」超时未响应，申请已过期");
                }
                for (PlayRequestBatch batch : affected) {
                    maybeSendSummary(batch);
                }
            }
        }
        int syncInterval = (CinemaForYou.serverConfig != null)
                ? CinemaForYou.serverConfig.syncIntervalMs : 5000;
        for (Map.Entry<UUID, RuntimeState> e : runtime.entrySet()) {
            RuntimeState rt = e.getValue();
            if (rt.state == ScreenState.PLAYING) {
                long delta = now - rt.lastServerTime;
                rt.positionMs += delta;
                rt.lastServerTime = now;
                rt.elapsedSinceBroadcast += delta;
                // 定期广播时钟校准
                if (rt.elapsedSinceBroadcast >= syncInterval) {
                    rt.dirty = true;
                    rt.elapsedSinceBroadcast = 0;
                }
            }
            if (rt.dirty) {
                rt.dirty = false;
                broadcastState(e.getKey());
            }
        }
    }

    private void broadcastState(UUID id) {
        CinemaScreen s = screens.get(id);
        if (s == null) return;
        RuntimeState rt = runtime.get(id);
        if (rt == null) return;
        ScreenStatePayload payload = new ScreenStatePayload(
                id, rt.state, rt.positionMs, rt.sourceUrl, System.currentTimeMillis());
        NetworkHandlers.broadcastState(payload);
    }

    /** 发送指定屏幕的当前状态给单个玩家（用于其加入时）。 */
    public void sendAllStates(ServerPlayer player, net.fabricmc.fabric.api.networking.v1.PacketSender sender) {
        for (Map.Entry<UUID, RuntimeState> e : runtime.entrySet()) {
            RuntimeState rt = e.getValue();
            if (rt.state == ScreenState.IDLE && rt.sourceUrl.isEmpty()) continue;
            ScreenStatePayload payload = new ScreenStatePayload(
                    e.getKey(), rt.state, rt.positionMs, rt.sourceUrl, System.currentTimeMillis());
            if (sender != null) {
                sender.sendPacket(payload);
            }
        }
    }

    // ───────────── 持久化 ─────────────

    private Path dataFile() {
        Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        Path dataDir = root.resolve("data");
        try {
            Files.createDirectories(dataDir);
        } catch (Exception ex) {
            LOGGER.error("[CinemaForYou] 创建 data 目录失败", ex);
        }
        return dataDir.resolve("cinemaforyou_screens.dat");
    }

    public void save() {
        try {
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (CinemaScreen s : screens.values()) {
                CompoundTag tag = new CompoundTag();
                putUUID(tag, "id", s.id());
                tag.putLong("c1", asLong(s.corner1()));
                tag.putLong("c2", asLong(s.corner2()));
                tag.putInt("orient", s.orientation().ordinal());
                tag.putString("url", s.sourceUrl());
                tag.putString("owner", s.ownerId());
                tag.putLong("created", s.createdAt());
                tag.putString("customId", s.customId() == null ? "" : s.customId());
                tag.putInt("brightness", s.brightnessPercent());
                tag.putInt("volume", s.volumePercent());
                tag.putInt("resolution", s.resolutionHeight());
                tag.putInt("scale", s.displayScalePercent());
                tag.putInt("audioRange", s.audioRangeBlocks());
                tag.putInt("audioFalloff", s.audioFalloffTenths());
                tag.putInt("curvType", s.curvatureType());
                tag.putInt("curvL", s.curvDegL());
                tag.putInt("curvR", s.curvDegR());
                tag.putInt("curvT", s.curvDegT());
                tag.putInt("curvB", s.curvDegB());
                tag.putInt("tiltH", s.tiltDegH());
                tag.putInt("tiltV", s.tiltDegV());
                list.add(tag);
            }
            root.put("screens", list);
            // 同时保存运行时状态
            ListTag rtList = new ListTag();
            for (Map.Entry<UUID, RuntimeState> e : runtime.entrySet()) {
                RuntimeState rt = e.getValue();
                if (rt.state == ScreenState.IDLE && rt.sourceUrl.isEmpty()) continue;
                CompoundTag tag = new CompoundTag();
                putUUID(tag, "id", e.getKey());
                tag.putInt("state", rt.state.ordinal());
                tag.putLong("pos", rt.positionMs);
                tag.putString("url", rt.sourceUrl);
                rtList.add(tag);
            }
            root.put("runtime", rtList);
            // 屏幕元数据（创建者显示名 + 描述）
            ListTag metaList = new ListTag();
            for (CinemaScreen s : screens.values()) {
                CompoundTag tag = new CompoundTag();
                putUUID(tag, "id", s.id());
                tag.putString("owner", screenOwnerNames.getOrDefault(s.id(), ""));
                tag.putString("desc", screenDescs.getOrDefault(s.id(), ""));
                metaList.add(tag);
            }
            root.put("meta", metaList);
            // 播放历史
            ListTag logList = new ListTag();
            for (LogEntry e : playLog) {
                CompoundTag tag = new CompoundTag();
                putUUID(tag, "id", e.id);
                tag.putLong("time", e.timeMs);
                tag.putString("puuid", e.playerUuid);
                tag.putString("pname", e.playerName);
                tag.putString("url", e.url);
                logList.add(tag);
            }
            root.put("playlog", logList);
            // 媒体文件归属
            ListTag ownerList = new ListTag();
            for (Map.Entry<String, String> e : mediaOwners.entrySet()) {
                CompoundTag tag = new CompoundTag();
                tag.putString("file", e.getKey());
                tag.putString("owner", e.getValue());
                ownerList.add(tag);
            }
            root.put("mediaOwners", ownerList);
            // 每屏播放队列（服务端唯一数据源）
            ListTag queueList = new ListTag();
            for (Map.Entry<UUID, List<QueueEntry>> e : queues.entrySet()) {
                for (QueueEntry qe : e.getValue()) {
                    CompoundTag tag = new CompoundTag();
                    putUUID(tag, "id", e.getKey());
                    tag.putString("url", qe.url());
                    tag.putString("player", qe.player());
                    tag.putLong("time", qe.timeMs());
                    queueList.add(tag);
                }
            }
            root.put("queues", queueList);
            // 全局播放队列（总设置入口，不参与自动连播）
            ListTag globalQueueList = new ListTag();
            for (QueueEntry qe : globalQueue) {
                CompoundTag tag = new CompoundTag();
                tag.putString("url", qe.url());
                tag.putString("player", qe.player());
                tag.putLong("time", qe.timeMs());
                globalQueueList.add(tag);
            }
            root.put("globalQueue", globalQueueList);

            NbtIo.writeCompressed(root, dataFile());
            LOGGER.debug("[CinemaForYou] 已保存 {} 个屏幕", screens.size());
        } catch (Exception ex) {
            LOGGER.error("[CinemaForYou] 保存失败", ex);
        }
    }

    public void load() {
        try {
            Path file = dataFile();
            if (!Files.exists(file)) {
                LOGGER.info("[CinemaForYou] 无持久化文件，跳过加载");
                return;
            }
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            ListTag list = root.getListOrEmpty("screens");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompoundOrEmpty(i);
                CinemaScreen s = new CinemaScreen(
                        getUUID(tag, "id"),
                        fromLong(tag.getLongOr("c1", 0)),
                        fromLong(tag.getLongOr("c2", 0)),
                        ScreenOrientation.values()[tag.getIntOr("orient", 0)],
                        tag.getStringOr("url", ""),
                        tag.getStringOr("owner", ""),
                        tag.getLongOr("created", 0L),
                        tag.getStringOr("customId", ""),
                        tag.getIntOr("brightness", 100),
                        tag.getIntOr("volume", 100),
                        tag.getIntOr("resolution", 720),
                        tag.getIntOr("scale", 100),
                tag.getIntOr("audioRange", 0),
                tag.getIntOr("audioFalloff", 0),
                tag.getIntOr("curvType", 0),
                migratedSide(tag, "curvL", "curvX", 300),
                migratedSide(tag, "curvR", "curvX", 300),
                migratedSide(tag, "curvT", "curvY", 150),
                migratedSide(tag, "curvB", "curvY", 150),
                tag.getIntOr("tiltH", 0),
                tag.getIntOr("tiltV", 0)
                );
                screens.put(s.id(), s);
                runtime.put(s.id(), new RuntimeState()); // 重启后回到 IDLE
            }
            // 加载运行时（只恢复 url，状态回到 IDLE 等待手动播放）
            ListTag rtList = root.getListOrEmpty("runtime");
            for (int i = 0; i < rtList.size(); i++) {
                CompoundTag tag = rtList.getCompoundOrEmpty(i);
                UUID id = getUUID(tag, "id");
                RuntimeState rt = runtime.get(id);
                if (rt != null) {
                    rt.sourceUrl = tag.getStringOr("url", "");
                    rt.state = ScreenState.IDLE; // 不自动续播
                }
            }
            // 屏幕元数据
            ListTag metaList = root.getListOrEmpty("meta");
            for (int i = 0; i < metaList.size(); i++) {
                CompoundTag tag = metaList.getCompoundOrEmpty(i);
                UUID id = getUUID(tag, "id");
                if (screens.containsKey(id)) {
                    String ownerName = tag.getStringOr("owner", "");
                    if (!ownerName.isEmpty()) {
                        screenOwnerNames.put(id, ownerName);
                    }
                    String desc = tag.getStringOr("desc", "");
                    if (!desc.isEmpty()) {
                        screenDescs.put(id, desc);
                    }
                }
            }
            // 播放历史
            ListTag logList = root.getListOrEmpty("playlog");
            for (int i = 0; i < logList.size() && playLog.size() < 300; i++) {
                CompoundTag tag = logList.getCompoundOrEmpty(i);
                playLog.add(new LogEntry(getUUID(tag, "id"),
                        tag.getLongOr("time", 0L),
                        tag.getStringOr("puuid", ""),
                        tag.getStringOr("pname", ""),
                        tag.getStringOr("url", "")));
            }
            // 媒体归属
            ListTag ownerList = root.getListOrEmpty("mediaOwners");
            for (int i = 0; i < ownerList.size(); i++) {
                CompoundTag tag = ownerList.getCompoundOrEmpty(i);
                String fname = tag.getStringOr("file", "");
                String owner = tag.getStringOr("owner", "");
                if (!fname.isEmpty() && !owner.isEmpty()) {
                    mediaOwners.put(fname, owner);
                }
            }
            // 每屏播放队列（只保留仍存在的屏幕）
            ListTag queueList = root.getListOrEmpty("queues");
            for (int i = 0; i < queueList.size(); i++) {
                CompoundTag tag = queueList.getCompoundOrEmpty(i);
                UUID id = getUUID(tag, "id");
                if (!screens.containsKey(id)) continue;
                String qurl = tag.getStringOr("url", "");
                if (qurl.isEmpty()) continue;
                queues.computeIfAbsent(id, k -> new ArrayList<>()).add(new QueueEntry(
                        id, qurl,
                        tag.getStringOr("player", ""),
                        tag.getLongOr("time", 0L)));
            }
            // 全局播放队列（不参与自动连播；屏幕为 null）
            ListTag globalQueueList = root.getListOrEmpty("globalQueue");
            for (int i = 0; i < globalQueueList.size(); i++) {
                CompoundTag tag = globalQueueList.getCompoundOrEmpty(i);
                String qurl = tag.getStringOr("url", "");
                if (qurl.isEmpty()) continue;
                globalQueue.add(new QueueEntry(null, qurl,
                        tag.getStringOr("player", ""),
                        tag.getLongOr("time", 0L), true));
            }
            LOGGER.info("[CinemaForYou] 已加载 {} 个屏幕", screens.size());
        } catch (Exception ex) {
            LOGGER.error("[CinemaForYou] 加载失败", ex);
        }
    }

    // ───────────── 辅助 ─────────────

    private void notFound(ServerPlayer p, UUID id) {
        p.sendSystemMessage(Component.literal("§c未找到屏幕 " + id));
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** BlockPos → long（用 BlockPos.asLong）。 */
    private static long asLong(BlockPos p) {
        return p.asLong();
    }

    private static BlockPos fromLong(long l) {
        return BlockPos.of(l);
    }

    /** 26.2 中 CompoundTag 无 putUUID/getUUID，用两个 long 存储 UUID。 */
    private static void putUUID(CompoundTag tag, String key, UUID uuid) {
        tag.putLong(key + "_msb", uuid.getMostSignificantBits());
        tag.putLong(key + "_lsb", uuid.getLeastSignificantBits());
    }

    private static UUID getUUID(CompoundTag tag, String key) {
        return new UUID(tag.getLongOr(key + "_msb", 0L), tag.getLongOr(key + "_lsb", 0L));
    }

    /** 屏幕运行时状态（不持久化时钟细节，只持久化 url）。 */
    private static class RuntimeState {
        ScreenState state = ScreenState.IDLE;
        String sourceUrl = "";
        long positionMs = 0;
        long lastServerTime = System.currentTimeMillis();
        boolean dirty = false;
        long elapsedSinceBroadcast = 0;
    }
}
