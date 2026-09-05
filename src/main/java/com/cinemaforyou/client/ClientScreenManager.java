package com.cinemaforyou.client;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.video.VideoPlayer;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenState;
import com.cinemaforyou.network.ScreenStatePayload;
import com.cinemaforyou.network.ScreenSyncPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 客户端屏幕状态管理器。
 *
 * <p>持有：
 * <ul>
 *   <li>所有屏幕定义 {@code Map<UUID, CinemaScreen>}（由 S2C 全量同步维护）</li>
 *   <li>每个屏幕的运行时状态（由 S2C 单屏状态包维护）</li>
 *   <li>每个屏幕的 {@link VideoPlayer} 实例（PLAYING 时启动，STOPPED 时释放）</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class ClientScreenManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("CinemaForYou/ClientMgr");

    private final Map<UUID, CinemaScreen> screens = new HashMap<>();
    private final Map<UUID, ClientState> states = new HashMap<>();
    private final Map<UUID, VideoPlayer> players = new HashMap<>();
    /** 每个屏最近一次写入历史记录的 URL（避免 5s 周期广播重复记录）。 */
    private final Map<UUID, String> lastRecordedUrl = new HashMap<>();

    // ───────────── 状态访问 ─────────────

    public static ClientScreenManager get() {
        return CinemaForYouClient.clientScreenManager;
    }

    public CinemaScreen getScreen(UUID id) {
        return screens.get(id);
    }

    public ScreenState getState(UUID id) {
        ClientState s = states.get(id);
        return s == null ? ScreenState.IDLE : s.state;
    }

    public Map<UUID, CinemaScreen> allScreens() {
        return screens;
    }

    /** 根据方块坐标查找命中的屏幕。 */
    public CinemaScreen findScreenAt(BlockPos pos) {
        if (pos == null) return null;
        for (CinemaScreen screen : screens.values()) {
            if (screen.contains(pos)) {
                return screen;
            }
        }
        return null;
    }

    /** 根据视线拾取屏幕，允许对准屏幕任意可见区域按 V 打开控制页。 */
    public CinemaScreen findScreenInSight(Vec3 eyePos, Vec3 lookDir, double maxDistance) {
        if (eyePos == null || lookDir == null) return null;
        double bestT = Double.POSITIVE_INFINITY;
        CinemaScreen best = null;

        for (CinemaScreen screen : screens.values()) {
            Double t = rayHitDistance(screen, eyePos, lookDir, maxDistance);
            if (t != null && t < bestT) {
                bestT = t;
                best = screen;
            }
        }
        return best;
    }

    /** 获取某屏幕的 VideoPlayer（可能为 null）。 */
    public VideoPlayer getPlayer(UUID id) {
        return players.get(id);
    }

    // ───────────── S2C 包处理 ─────────────

    /** 处理全量屏幕列表（玩家加入或屏幕增删时）。 */
    public void handleSync(ScreenSyncPayload payload) {
        Map<UUID, CinemaScreen> oldMap = new HashMap<>(screens);
        // 全量替换：先移除已不存在的屏幕对应的 VideoPlayer
        Map<UUID, CinemaScreen> newMap = new HashMap<>();
        for (CinemaScreen s : payload.screens()) {
            newMap.put(s.id(), s);
        }
        // 移除不再存在的
        screens.keySet().removeIf(id -> {
            if (!newMap.containsKey(id)) {
                stopAndRemovePlayer(id);
                states.remove(id);
                lastRecordedUrl.remove(id);
                return true;
            }
            return false;
        });
        // 加入/更新
        screens.clear();
        screens.putAll(newMap);

        for (Map.Entry<UUID, CinemaScreen> entry : newMap.entrySet()) {
            UUID id = entry.getKey();
            CinemaScreen newScreen = entry.getValue();
            VideoPlayer player = players.get(id);
            if (player == null) continue;
            CinemaScreen oldScreen = oldMap.get(id);
            player.updateScreen(newScreen);
            if (oldScreen != null && oldScreen.resolutionHeight() != newScreen.resolutionHeight()) {
                ClientState st = states.get(id);
                long restartPos = player.getPositionMs();
                String sourceUrl = player.getSourceUrl();
                player.release();
                players.remove(id);
                if (sourceUrl != null && !sourceUrl.isEmpty()) {
                    VideoPlayer restarted = new VideoPlayer(id, newScreen, sourceUrl);
                    restarted.start(restartPos);
                    if (st != null && st.state == ScreenState.PAUSED) {
                        restarted.pause();
                    }
                    players.put(id, restarted);
                }
            }
        }
        LOGGER.debug("已同步 {} 个屏幕", screens.size());
    }

    /** 处理单屏状态变更。 */
    public void handleStateChange(ScreenStatePayload payload) {
        UUID id = payload.id();
        CinemaScreen screen = screens.get(id);
        if (screen == null) {
            LOGGER.warn("收到未知屏幕 {} 的状态变更", id);
            return;
        }

        ClientState st = states.computeIfAbsent(id, k -> new ClientState());
        ScreenState oldState = st.state;
        st.state = payload.state();
        st.positionMs = payload.positionMs();
        st.sourceUrl = payload.sourceUrl();
        st.serverTimeMs = payload.serverTimeMs();
        st.lastSyncLocalMs = System.currentTimeMillis();

        // 估算网络延迟后的服务端当前位置
        long netDelay = System.currentTimeMillis() - payload.serverTimeMs();
        long expectedPos = payload.positionMs() + Math.max(0, netDelay);

        LOGGER.debug("屏幕 {} 状态: {} → {} (pos={}ms, expected={}ms)", id, oldState, st.state, st.positionMs, expectedPos);

        // 根据 state 切换 VideoPlayer
        switch (payload.state()) {
            case PLAYING -> {
                recordHistory(id, st.sourceUrl);
                VideoPlayer existing = players.get(id);
                if (existing != null && existing.getSourceUrl().equals(st.sourceUrl)) {
                    // 播放器已出错（解码失败等）：不做漂移修正，
                    // 等待服务端 STOP 或 URL 变更后重建，避免每 5 秒无意义 seek 刷屏
                    if (existing.getError() != null) {
                        return;
                    }
                    // 上一条已播完（循环/重播/自动下一集场景）：
                    // 仅当服务端位置明显早于结尾（真重播）时才重建播放器；
                    // 位置仍接近结尾 = 周期校准广播，保持末帧等所有者操作。
                    if (existing.hasEnded()) {
                        long dur = existing.getDurationMs();
                        boolean restart = dur <= 0 || expectedPos < Math.max(1000L, dur - 1500L);
                        if (restart) {
                            existing.release();
                            players.remove(id);
                            ensurePlayer(id, st.sourceUrl, expectedPos);
                        }
                        return;
                    }
                    // 同 URL 已在播放：检测位置漂移
                    long localPos = existing.getPositionMs();
                    long drift = Math.abs(localPos - expectedPos);
                    if (drift > 2000) {
                        LOGGER.debug("屏幕 {} 位置漂移 {}ms，seek 修正到 {}ms", id, drift, expectedPos);
                        existing.seek(expectedPos);
                    }
                    existing.resume();
                } else {
                    // 新 URL 或无播放器：重建
                    ensurePlayer(id, st.sourceUrl, expectedPos);
                }
            }
            case PAUSED -> {
                VideoPlayer vp = players.get(id);
                if (vp != null && vp.getError() == null) {
                    // 暂停时也检测漂移（seek-while-paused 同步）
                    long localPos = vp.getPositionMs();
                    long drift = Math.abs(localPos - expectedPos);
                    if (drift > 1000) {
                        vp.seek(expectedPos);
                    }
                    vp.pause();
                }
            }
            case STOPPED, IDLE -> {
                stopAndRemovePlayer(id);
                lastRecordedUrl.remove(id);
            }
        }
    }

    // ───────────── tick ─────────────

    /** 每帧调用：更新 VideoPlayer 纹理。 */
    public void tick() {
        for (VideoPlayer vp : players.values()) {
            vp.tick();
        }
    }

    /** 记录一次"开始播放某源"到客户端历史（同一屏同一 URL 只记一次）。 */
    private void recordHistory(UUID id, String url) {
        if (url == null || url.isEmpty()) return;
        com.cinemaforyou.client.config.ClientConfig cfg =
                com.cinemaforyou.CinemaForYouClient.clientConfig;
        if (cfg == null) return;
        if (url.equals(lastRecordedUrl.get(id))) return;
        lastRecordedUrl.put(id, url);
        cfg.addHistory(url);
    }

    // ───────────── VideoPlayer 管理 ─────────────

    private void ensurePlayer(UUID id, String sourceUrl, long startPos) {
        VideoPlayer existing = players.get(id);
        if (existing != null) {
            // URL 变了则重建
            if (!sourceUrl.equals(existing.getSourceUrl())) {
                existing.release();
                players.remove(id);
            } else {
                existing.resume();
                return;
            }
        }
        if (sourceUrl.isEmpty()) return;
        CinemaScreen screen = screens.get(id);
        if (screen == null) return;
        VideoPlayer vp = new VideoPlayer(id, screen, sourceUrl);
        vp.start(startPos);
        players.put(id, vp);
    }

    private void pausePlayer(UUID id) {
        VideoPlayer vp = players.get(id);
        if (vp != null) vp.pause();
    }

    private void stopAndRemovePlayer(UUID id) {
        VideoPlayer vp = players.remove(id);
        if (vp != null) vp.release();
    }

    private static Double rayHitDistance(CinemaScreen screen, Vec3 eyePos, Vec3 lookDir, double maxDistance) {
        double minX = Math.min(screen.corner1().getX(), screen.corner2().getX());
        double maxX = Math.max(screen.corner1().getX(), screen.corner2().getX()) + 1.0;
        double minY = Math.min(screen.corner1().getY(), screen.corner2().getY());
        double maxY = Math.max(screen.corner1().getY(), screen.corner2().getY()) + 1.0;
        double minZ = Math.min(screen.corner1().getZ(), screen.corner2().getZ());
        double maxZ = Math.max(screen.corner1().getZ(), screen.corner2().getZ()) + 1.0;

        double scale = Math.max(0.25, screen.displayScalePercent() / 100.0);
        double centerX = (minX + maxX) * 0.5;
        double centerY = (minY + maxY) * 0.5;
        double centerZ = (minZ + maxZ) * 0.5;
        double halfX = (maxX - minX) * 0.5 * scale;
        double halfY = (maxY - minY) * 0.5 * scale;
        double halfZ = (maxZ - minZ) * 0.5 * scale;

        minX = centerX - halfX;
        maxX = centerX + halfX;
        minY = centerY - halfY;
        maxY = centerY + halfY;
        minZ = centerZ - halfZ;
        maxZ = centerZ + halfZ;

        final double planeOffset = 1.01;
        double t;
        switch (screen.orientation()) {
            case AXIS_Z -> {
                if (Math.abs(lookDir.z) < 1.0E-6) return null;
                double planeZ = Math.max(screen.corner1().getZ(), screen.corner2().getZ()) + planeOffset;
                t = (planeZ - eyePos.z) / lookDir.z;
                if (t < 0 || t > maxDistance) return null;
                double x = eyePos.x + lookDir.x * t;
                double y = eyePos.y + lookDir.y * t;
                if (x < minX || x > maxX || y < minY || y > maxY) return null;
            }
            case AXIS_X -> {
                if (Math.abs(lookDir.x) < 1.0E-6) return null;
                double planeX = Math.max(screen.corner1().getX(), screen.corner2().getX()) + planeOffset;
                t = (planeX - eyePos.x) / lookDir.x;
                if (t < 0 || t > maxDistance) return null;
                double y = eyePos.y + lookDir.y * t;
                double z = eyePos.z + lookDir.z * t;
                if (y < minY || y > maxY || z < minZ || z > maxZ) return null;
            }
            case AXIS_Y -> {
                if (Math.abs(lookDir.y) < 1.0E-6) return null;
                double planeY = Math.max(screen.corner1().getY(), screen.corner2().getY()) + planeOffset;
                t = (planeY - eyePos.y) / lookDir.y;
                if (t < 0 || t > maxDistance) return null;
                double x = eyePos.x + lookDir.x * t;
                double z = eyePos.z + lookDir.z * t;
                if (x < minX || x > maxX || z < minZ || z > maxZ) return null;
            }
            default -> {
                return null;
            }
        }
        return t;
    }

    // ───────────── 清理 ─────────────

    /** 玩家断线时释放所有资源。 */
    public void cleanup() {
        for (VideoPlayer vp : players.values()) {
            vp.release();
        }
        players.clear();
        screens.clear();
        states.clear();
        lastRecordedUrl.clear();
    }

    /** 客户端运行时状态。 */
    private static class ClientState {
        ScreenState state = ScreenState.IDLE;
        String sourceUrl = "";
        long positionMs = 0;
        long serverTimeMs = 0;
        long lastSyncLocalMs = 0;
    }
}
