package com.cinemaforyou.client.render;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.video.VideoPlayer;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

/**
 * 世界内屏幕渲染器（26.2 LevelRenderEvents）。
 *
 * <p>在 {@link LevelRenderEvents#AFTER_TRANSLUCENT_FEATURES} 阶段绘制每个屏幕为贴图四边形。
 * 纹理来自 {@link VideoPlayer} 解码并注册到 {@link Identifier} 的动态纹理。
 *
 * <p>错误反馈：当某屏幕的 {@link VideoPlayer} 进入错误状态且尚未报告时，
 * 通过聊天条向玩家发送一次错误信息并标记已报告，避免重复刷屏。
 *
 * <p>调试信息：当 {@link ClientConfig#showDebugInfo} 为 true 时，每隔约 2 秒
 * 向玩家发送一次附近屏幕的 ID/状态/位置概要，方便定位问题。
 */
@Environment(EnvType.CLIENT)
public class ScreenRenderer {

    /** 调试信息推送间隔（毫秒）。 */
    private static final long DEBUG_INFO_INTERVAL_MS = 2000;
    /** 调试信息覆盖的最大距离平方（块²）。 */
    private static final double DEBUG_INFO_RANGE_SQ = 64.0 * 64.0;
    private static final Path DEBUG_LOG =
            Path.of("d:/Minecraft_Project/CinemaForYou/.dbg/trae-debug-log-video-link-stutter.ndjson");

    private static long lastDebugInfoMs = 0L;
    private static long lastRenderLogMs = 0L;

    public static void register() {
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context ->
                renderScreens(context));
    }

    private static void renderScreens(LevelRenderContext context) {
        ClientScreenManager mgr = CinemaForYouClient.clientScreenManager;
        if (mgr == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // 每渲染帧驱动所有播放器：tick() 会把"已到呈现时间"的视频帧上传纹理，
        // 使画面更新频率 = 游戏渲染帧率（不再被 20Hz 客户端 tick 锁死）。
        // 方法幂等：纹理无新帧时几乎零开销；客户端 tick 里另有同款兜底调用。
        mgr.tick();

        // 26.2 关键：feature 提交阶段的 PoseStack 是恒等矩阵（不含相机位移），
        // 原版实体/方块实体渲染都手动减去相机坐标（见 LevelRenderer.submitEntities）。
        // 这里同样整体平移 -cameraPos，否则几何体会被画到错误位置而不可见。
        com.mojang.blaze3d.vertex.PoseStack pose = context.poseStack();
        net.minecraft.world.phys.Vec3 cam = context.levelState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate((float) -cam.x, (float) -cam.y, (float) -cam.z);
        try {
            renderScreensInner(context, mc, mgr);
        } finally {
            pose.popPose();
        }
    }

    private static void renderScreensInner(
            LevelRenderContext context, Minecraft mc, ClientScreenManager mgr) {

        ClientConfig cfg = CinemaForYouClient.clientConfig;
        int renderDist = (cfg != null) ? cfg.renderDistance : 128;
        double renderDistSq = (double) renderDist * renderDist;

        boolean showDebug = cfg != null && cfg.showDebugInfo;
        long now = System.currentTimeMillis();
        boolean pushDebug = showDebug && (now - lastDebugInfoMs) >= DEBUG_INFO_INTERVAL_MS;
        if (pushDebug) lastDebugInfoMs = now;

        LocalPlayer player = mc.player;

        for (Map.Entry<UUID, CinemaScreen> entry : mgr.allScreens().entrySet()) {
            UUID id = entry.getKey();
            CinemaScreen screen = entry.getValue();

            // 距离裁剪：超过配置距离不渲染
            BlockPos center = screen.center();
            double distSq = player.distanceToSqr(center.getX(), center.getY(), center.getZ());
            if (distSq > renderDistSq) continue;

            VideoPlayer vp = mgr.getPlayer(id);
            Identifier textureId = (vp != null) ? vp.getTextureId() : null;

            // 渲染：黑底 + 视频纹理（相机坐标用于视频外扩防 Z-fighting）
            var camPos = context.levelState().cameraRenderState.pos;
            ScreenQuad.render(
                    context.submitNodeCollector(),
                    context.poseStack(),
                    screen,
                    textureId,
                    new double[]{camPos.x, camPos.y, camPos.z});
            if (textureId == null) {
                long nowLog = System.currentTimeMillis();
                if (lastRenderLogMs == 0L || nowLog - lastRenderLogMs >= 2000L) {
                    lastRenderLogMs = nowLog;
                    // #region debug-point D:texture-missing
                    debugPoint("D", "ScreenRenderer.renderScreensInner:102",
                            "[DEBUG] screen rendered without texture",
                            "screenId", id,
                            "state", mgr.getState(id),
                            "sourceUrl", truncate(screen.sourceUrl(), 120),
                            "distSq", distSq);
                    // #endregion
                }
            }

            // 错误反馈：首次发现错误时通过聊天告知玩家，并上报服务端停止该屏幕
            // （否则服务端状态会永远停留在 PLAYING，控制界面一直显示"正在播放"）
            if (vp != null) {
                String err = vp.getError();
                if (err != null && !vp.isErrorReported()) {
                    player.sendSystemMessage(Component.literal(
                            "[CinemaForYou] 屏幕 " + id + " 播放失败: " + err));
                    com.cinemaforyou.client.network.ClientNetworkHandlers.sendAction(
                            com.cinemaforyou.network.ScreenActionPayload.reportError(id, err));
                    vp.markErrorReported();
                }
            }

            // 调试信息：仅在范围内推送，避免长串聊天刷屏
            if (pushDebug && distSq <= DEBUG_INFO_RANGE_SQ) {
                ScreenState st = mgr.getState(id);
                String url = (vp != null) ? vp.getSourceUrl() : screen.sourceUrl();
                if (url == null || url.isEmpty()) url = "<空>";
                String tex = textureId != null ? "tex=OK" : "tex=null";
                String err = (vp != null && vp.getError() != null) ? (" err=" + truncate(vp.getError(), 40)) : "";
                player.sendSystemMessage(Component.literal(
                        "[Cinema] " + shortId(id) + " @ " + posStr(center)
                                + " state=" + st
                                + " " + tex
                                + " size=" + screen.width() + "x" + screen.height()
                                + " src=" + truncate(url, 40)
                                + err));
            }
        }

        renderSelectionPreview(context, mc, cfg);
    }

    /**
     * 对角点选择预览：
     * <ul>
     *   <li>选择中（角点1已定）：绿色盒体从角点1实时延伸到准星目标方块</li>
     *   <li>确认中（URL 输入屏打开）：黄色盒体显示最终范围</li>
     * </ul>
     * 可在设置界面通过 {@code showSelectionBox} 关闭。
     */
    private static void renderSelectionPreview(
            LevelRenderContext context, Minecraft mc, ClientConfig cfg) {
        if (cfg != null && !cfg.showSelectionBox) return;
        if (!SelectionPreview.isSelecting()
                && SelectionPreview.confirmingCorner2() == null) return;

        net.minecraft.world.phys.AABB box = null;
        if (SelectionPreview.isSelecting()) {
            BlockPos c1 = SelectionPreview.selectingCorner1();
            // 实时预览：延伸到准星目标方块（盒体已外扩防 Z-fighting）
            if (mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                    && hit.getBlockPos() != null) {
                box = SelectionPreview.boxOf(c1, hit.getBlockPos());
            }
        } else {
            box = SelectionPreview.currentBox();
        }

        if (box != null) {
            boolean confirming = SelectionPreview.confirmingCorner2() != null;
            // 绿色 = 选择中；黄色 = 确认中（半透明填充）
            ScreenQuad.renderBox(context.submitNodeCollector(), context.poseStack(),
                    box, confirming ? 255 : 80, confirming ? 210 : 255, confirming ? 40 : 120, confirming ? 130 : 120);
        }
    }

    /** 截取 UUID 前 8 位用于日志显示。 */
    private static String shortId(UUID id) {
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    /** 格式化 BlockPos 为 "x,y,z"。 */
    private static String posStr(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** 截断超长字符串并加省略号。 */
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // #region debug-point D:helper
    private static void debugPoint(String hypothesisId, String location, String msg, Object... kvPairs) {
        try {
            Files.createDirectories(DEBUG_LOG.getParent());
            StringBuilder json = new StringBuilder();
            json.append("{\"sessionId\":\"video-link-stutter\",\"runId\":\"post-fix\",\"hypothesisId\":\"")
                    .append(escapeJson(hypothesisId)).append("\",\"location\":\"")
                    .append(escapeJson(location)).append("\",\"msg\":\"")
                    .append(escapeJson(msg)).append("\",\"data\":{");
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                if (i > 0) json.append(',');
                json.append('"').append(escapeJson(String.valueOf(kvPairs[i]))).append("\":\"")
                        .append(escapeJson(String.valueOf(kvPairs[i + 1]))).append('"');
            }
            json.append("},\"ts\":").append(System.currentTimeMillis()).append("}");
            Files.writeString(DEBUG_LOG, json.append(System.lineSeparator()).toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
    // #endregion
}
