package com.cinemaforyou.manager;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.config.ServerConfig;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenOrientation;
import com.cinemaforyou.data.ScreenState;
import com.cinemaforyou.network.NetworkHandlers;
import com.cinemaforyou.network.ScreenStatePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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

    private static final Logger LOGGER = CinemaForYou.LOGGER;

    private final MinecraftServer server;
    private final Map<UUID, CinemaScreen> screens = new HashMap<>();
    private final Map<UUID, RuntimeState> runtime = new HashMap<>();

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
        save();
        NetworkHandlers.broadcastSync(allScreens());
        owner.sendSystemMessage(Component.literal(
                "§a[CinemaForYou] 屏幕已创建，ID: " + screen.displayName()));
        return screen;
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
        save();
        NetworkHandlers.broadcastSync(allScreens());
        requester.sendSystemMessage(Component.literal("§a已删除屏幕 " + id));
        return true;
    }

    // ───────────── 播放控制 ─────────────

    public void play(UUID id, String url, ServerPlayer requester) {
        CinemaScreen s = screens.get(id);
        if (s == null) { notFound(requester, id); return; }
        // 配置校验：本地文件与域名白名单
        ServerConfig cfg = CinemaForYou.serverConfig;
        if (cfg != null) {
            if (!cfg.isLocalFileAllowed(url)) {
                requester.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 服务端已禁止播放本地文件"));
                return;
            }
            if (!cfg.isUrlAllowed(url)) {
                requester.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 此视频域名不在白名单中"));
                return;
            }
        }
        // 服务端媒体大文件自动转封装：命中需优化的容器时先排队转封装，
        // 完成后自动开始播放（避免远程玩家播放时的探测跳读网络开销）
        if (url.startsWith("file:")) {
            String fileName = new java.io.File(url.substring("file:".length())).getName();
            if (MediaRemuxer.maybeDeferPlay(id, requester, fileName)) {
                return;
            }
        }
        // 本地文件优先改写成服务端媒体地址（文件须在 服务器目录/cinema/videos/ 下，
        // 这样服务器上其它玩家也能拉流观看）；改不了才保持原样（仅本机可见）。
        String effective = url;
        boolean served = false;
        if (url.startsWith("file:")) {
            effective = com.cinemaforyou.manager.MediaHttpServer.mapLocalFileToHttp(url);
            served = !effective.equals(url);
        }
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
        requester.sendSystemMessage(Component.literal(
                "§a▶ 播放: " + truncate(served ? effective : url, 60)));
        if (url.startsWith("file:") && !served) {
            requester.sendSystemMessage(Component.literal(
                    "§7提示：服务端 cinema/videos/ 下没有同名文件，只有本机客户端能看到该画面。"
                            + "想让所有人观看，请把视频放进服务器目录 cinema/videos/ 后重新播放。"));
        }
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

    /** 每 tick 调用：推进 PLAYING 屏幕时钟；定期刷新状态用于客户端校准。 */
    public void tick() {
        long now = System.currentTimeMillis();
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
