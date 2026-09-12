package com.cinemaforyou.client.network;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.network.QueueEntry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端播放队列的客户端只读镜像 + 本地持久化副本。
 *
 * <p>服务端是队列唯一数据源：任何变更（增/删/清空/上移下移/全局队列操作/迁移上传）都会广播
 * {@link com.cinemaforyou.network.ScreenQueuePayload}，这里整体替换镜像并通知界面刷新。
 * "播完行为=自动播放下一个"只读每屏队列（{@link #entriesFor(UUID)}，全局条目 screenId 为 null
 * 不会命中，因此天然不参与自动连播）；全局播放队列只由总设置的「全局播放队列管理」视图读取
 * （{@link #globalEntries()}），手动点击才向所有屏幕发播放申请。
 *
 * <p>本地持久化副本：镜像内容同时写入 {@link ClientConfig#queueMirror}（每屏）与
 * {@link ClientConfig#globalQueueMirror}（全局）并落盘，重启游戏后服务端同步到达前界面可先显示
 * 这份副本（见 {@link #initFromLocalMirror()}）；服务端广播到达后以服务端数据整体覆盖并落盘，
 * 服务端权威性不变。
 *
 * <p>乐观更新：客户端发出操作请求后先本地改镜像（界面立即响应），
 * 服务端广播随后到达覆盖为权威顺序（与 {@code localApplyScreen} 同一思路）。
 */
@Environment(EnvType.CLIENT)
public final class QueueClient {

    private static volatile List<QueueEntry> entries = List.of();
    /** 是否已收到过服务端队列同步（收到才尝试旧版本机队列迁移）。 */
    private static volatile boolean synced = false;
    /** 本次会话是否已尝试过旧队列上传（避免重复发包）。 */
    private static boolean legacyUploadSent = false;
    private static Runnable listener = null;

    private QueueClient() {}

    /**
     * 客户端启动时调用：把本地持久化副本（{@link ClientConfig#queueMirror}）灌入内存镜像。
     *
     * <p>此时尚未收到服务端同步，界面（屏幕控制页 / 总设置全屏视图）先显示这份本地副本；
     * 服务端队列广播到达后由 {@link #accept(List)} 整体覆盖。
     */
    public static void initFromLocalMirror() {
        entries = localMirrorEntries();
        synced = false;   // 仍未与服务端同步
    }

    /** 收到服务端全量队列（S2C）。服务端数据同时覆盖本地持久化副本并落盘。 */
    public static void accept(List<QueueEntry> list) {
        entries = list == null ? List.of() : List.copyOf(list);
        synced = true;
        persistMirror();
        maybeUploadLegacyQueue();
        Runnable l = listener;
        if (l != null) {
            Minecraft.getInstance().execute(l);
        }
    }

    /** 全部条目（只读）。 */
    public static List<QueueEntry> entries() {
        return entries;
    }

    /** 某屏队列（按服务端顺序，只读；不含全局播放队列条目）。 */
    public static List<QueueEntry> entriesFor(UUID screenId) {
        if (screenId == null) return List.of();
        List<QueueEntry> out = new ArrayList<>();
        for (QueueEntry e : entries) {
            if (screenId.equals(e.screenId())) {
                out.add(e);
            }
        }
        return out;
    }

    /** 全局播放队列（总设置入口，只读；不参与任何屏幕的自动连播）。 */
    public static List<QueueEntry> globalEntries() {
        List<QueueEntry> out = new ArrayList<>();
        for (QueueEntry e : entries) {
            if (e.global()) out.add(e);
        }
        return out;
    }

    /** 全局播放队列条目数。 */
    public static int globalCount() {
        int n = 0;
        for (QueueEntry e : entries) {
            if (e.global()) n++;
        }
        return n;
    }

    /** 某屏队列条目数（不含全局播放队列条目）。 */
    public static int countFor(UUID screenId) {
        if (screenId == null) return 0;
        int n = 0;
        for (QueueEntry e : entries) {
            if (!e.global() && screenId.equals(e.screenId())) n++;
        }
        return n;
    }

    /** 界面刷新监听（打开队列界面时注册）。 */
    public static void setListener(Runnable r) {
        listener = r;
    }

    /**
     * 断线清理（配置里的迁移标记与本地持久化副本保留，避免重复上传 / 丢失展示数据）。
     *
     * <p>内存镜像回落到本地持久化副本：下次连入服务端、同步到达前界面仍可先显示上次的队列。
     */
    public static void reset() {
        synced = false;
        legacyUploadSent = false;
        entries = localMirrorEntries();
    }

    // ───────────── 乐观本地更新（同时更新本地持久化副本并落盘） ─────────────

    /** 本地删除某屏队列第 index 项。 */
    public static void localRemove(UUID screenId, int index) {
        List<QueueEntry> out = new ArrayList<>(entries);
        int seen = -1;
        for (int i = 0; i < out.size(); i++) {
            QueueEntry e = out.get(i);
            if (screenId != null && screenId.equals(e.screenId())) {
                seen++;
                if (seen == index) {
                    out.remove(i);
                    break;
                }
            }
        }
        entries = List.copyOf(out);
        persistMirror();
    }

    /** 本地清空某屏队列。 */
    public static void localClear(UUID screenId) {
        if (screenId == null) return;
        List<QueueEntry> out = new ArrayList<>(entries);
        out.removeIf(e -> screenId.equals(e.screenId()));
        entries = List.copyOf(out);
        persistMirror();
    }

    /** 本地调整某屏队列顺序。 */
    public static void localMove(UUID screenId, int from, int to) {
        if (screenId == null) return;
        List<QueueEntry> out = new ArrayList<>(entries);
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (screenId.equals(out.get(i).screenId())) idx.add(i);
        }
        if (from < 0 || from >= idx.size() || to < 0 || to >= idx.size() || from == to) return;
        QueueEntry item = out.remove((int) idx.get(from));
        // 删除后目标下标对应的位置：在原列表中重新定位
        List<Integer> idx2 = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (screenId.equals(out.get(i).screenId())) idx2.add(i);
        }
        int insertAt = to >= idx2.size() ? (idx2.isEmpty() ? out.size() : idx2.get(idx2.size() - 1) + 1)
                : idx2.get(to);
        out.add(insertAt, item);
        entries = List.copyOf(out);
        persistMirror();
    }

    /** 本地删除全局播放队列第 index 项。 */
    public static void localRemoveGlobal(int index) {
        List<QueueEntry> out = new ArrayList<>(entries);
        int seen = -1;
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).global()) {
                seen++;
                if (seen == index) {
                    out.remove(i);
                    break;
                }
            }
        }
        entries = List.copyOf(out);
        persistMirror();
    }

    /** 本地清空全局播放队列。 */
    public static void localClearGlobal() {
        List<QueueEntry> out = new ArrayList<>(entries);
        out.removeIf(QueueEntry::global);
        entries = List.copyOf(out);
        persistMirror();
    }

    /** 本地调整全局播放队列顺序。 */
    public static void localMoveGlobal(int from, int to) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).global()) idx.add(i);
        }
        if (from < 0 || from >= idx.size() || to < 0 || to >= idx.size() || from == to) return;
        List<QueueEntry> out = new ArrayList<>(entries);
        QueueEntry item = out.remove((int) idx.get(from));
        // 删除后重新定位全局条目的下标
        List<Integer> idx2 = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).global()) idx2.add(i);
        }
        int insertAt = to >= idx2.size()
                ? (idx2.isEmpty() ? out.size() : idx2.get(idx2.size() - 1) + 1)
                : idx2.get(to);
        out.add(insertAt, item);
        entries = List.copyOf(out);
        persistMirror();
    }

    // ───────────── 本地持久化副本 ─────────────

    /** 内存镜像 → 本地副本条目列表（读 {@link ClientConfig#queueMirror} 与全局副本；损坏键/条目跳过）。 */
    private static List<QueueEntry> localMirrorEntries() {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg == null) return List.of();
        List<QueueEntry> out = new ArrayList<>();
        if (cfg.queueMirror != null) {
            for (Map.Entry<String, List<ClientConfig.QueueItem>> e : cfg.queueMirror.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                UUID id;
                try {
                    id = UUID.fromString(e.getKey());
                } catch (Exception ignored) {
                    continue;   // 损坏的屏幕键：跳过
                }
                for (ClientConfig.QueueItem item : e.getValue()) {
                    if (item == null || item.url == null || item.url.isEmpty()) continue;
                    out.add(new QueueEntry(id, item.url,
                            item.player == null ? "" : item.player, item.time));
                }
            }
        }
        // 全局播放队列本地副本（不参与自动连播，屏幕为 null）
        if (cfg.globalQueueMirror != null) {
            for (ClientConfig.QueueItem item : cfg.globalQueueMirror) {
                if (item == null || item.url == null || item.url.isEmpty()) continue;
                out.add(new QueueEntry(null, item.url,
                        item.player == null ? "" : item.player, item.time, true));
            }
        }
        return List.copyOf(out);
    }

    /**
     * 内存镜像按范围写回本地持久化副本并落盘（服务端同步 / 乐观本地更新后调用）。
     *
     * <p>每屏条目写 {@link ClientConfig#queueMirror}，全局播放队列条目写
     * {@link ClientConfig#globalQueueMirror}，保持两种范围的区分。
     *
     * <p>{@link ClientConfig#save()} 为「标记脏 + 节流异步落盘」（最多每 500ms 真正写盘一次，
     * 磁盘 IO 在单线程守护线程上做），这里的调用会与界面、标题线程的保存自动合并，不会卡顿。
     */
    private static void persistMirror() {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg == null) return;
        Map<String, List<ClientConfig.QueueItem>> mirror = new HashMap<>();
        List<ClientConfig.QueueItem> globalMirror = new ArrayList<>();
        for (QueueEntry e : entries) {
            if (e == null) continue;
            if (e.global()) {
                globalMirror.add(new ClientConfig.QueueItem(e.url(), e.player(), e.timeMs()));
                continue;
            }
            if (e.screenId() == null) continue;
            mirror.computeIfAbsent(e.screenId().toString(), k -> new ArrayList<>())
                    .add(new ClientConfig.QueueItem(e.url(), e.player(), e.timeMs()));
        }
        cfg.queueMirror = mirror;
        cfg.globalQueueMirror = globalMirror;
        cfg.save();
    }

    // ───────────── 旧版本机队列迁移 ─────────────

    /**
     * 首次收到服务端队列同步时，把本机旧队列（ClientConfig.screenPlaylist）整体上传，
     * 避免用户此前排好的队列丢失；上传后不再使用本机队列。
     */
    private static void maybeUploadLegacyQueue() {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg == null || legacyUploadSent || cfg.queueMigratedToServer) return;
        legacyUploadSent = true;
        List<QueueEntry> out = new ArrayList<>();
        if (cfg.screenPlaylist != null) {
            for (var entry : cfg.screenPlaylist.entrySet()) {
                UUID id;
                try {
                    id = UUID.fromString(entry.getKey());
                } catch (Exception ignored) {
                    continue;   // 旧配置里损坏的键：跳过
                }
                List<String> urls = entry.getValue();
                if (urls == null) continue;
                List<ClientConfig.QueueItem> infos = cfg.screenPlaylistInfo != null
                        ? cfg.screenPlaylistInfo.get(entry.getKey()) : null;
                for (int i = 0; i < urls.size(); i++) {
                    String url = urls.get(i);
                    if (url == null || url.isEmpty()) continue;
                    ClientConfig.QueueItem info =
                            (infos != null && i < infos.size()) ? infos.get(i) : null;
                    out.add(new QueueEntry(id, url,
                            info != null ? info.player : "",
                            info != null ? info.time : 0L));
                }
            }
        }
        // 上传后即弃用本机队列（服务端按权限过滤并入；失败时服务端会回执提示）
        cfg.screenPlaylist = new java.util.HashMap<>();
        cfg.screenPlaylistInfo = new java.util.HashMap<>();
        cfg.queueMigratedToServer = true;
        cfg.save();
        if (out.isEmpty()) return;
        ClientNetworkHandlers.sendQueueUpload(out);
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] 已把本机播放队列（" + out.size() + " 项）上传到服务器统一管理"));
        }
    }
}
