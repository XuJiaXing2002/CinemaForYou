package com.cinemaforyou;

import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.gui.ClientSelectorHook;
import com.cinemaforyou.client.gui.CinemaSettingsScreen;
import com.cinemaforyou.client.gui.ScreenControlScreen;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.render.ScreenRenderer;
import com.cinemaforyou.client.video.YtDlpDownloader;
import com.cinemaforyou.item.CinemaSelectorItem;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CinemaForYou 客户端入口点。
 *
 * <p>客户端职责：接收服务端屏幕列表与状态，使用 JavaCV/FFmpeg 解码视频，
 * 用 Blaze3D 渲染为世界内贴图四边形，OpenAL 播放位置音频。
 *
 * <p>关键依赖：
 * <ul>
 *   <li>{@link com.cinemaforyou.client.video.VideoPlayer} - 视频解码线程</li>
 *   <li>{@link com.cinemaforyou.client.render.ScreenRenderer} - 世界渲染钩子</li>
 *   <li>{@link com.cinemaforyou.client.audio.AudioPlayer} - 位置音频</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class CinemaForYouClient implements ClientModInitializer {

    public static final Logger CLIENT_LOGGER = LoggerFactory.getLogger(CinemaForYouClient.class);

    /** 客户端屏幕状态缓存（由 S2C 包维护）。 */
    public static ClientScreenManager clientScreenManager;

    /** 客户端配置单例。 */
    public static ClientConfig clientConfig;

    /** 打开影院设置/屏幕控制的按键（默认 V，可在 选项→控制 中修改）。 */
    public static KeyMapping openSettingsKey;

    @Override
    public void onInitializeClient() {
        CLIENT_LOGGER.info("[CinemaForYou] 初始化客户端...");

        // 部分启动器环境默认 java.awt.headless=true，会导致系统文件对话框抛
        // HeadlessException（完全无法弹出）。必须在 AWT Toolkit 初始化前关闭。
        try {
            System.setProperty("java.awt.headless", "false");
        } catch (Exception ignored) {}

        // 0. 加载客户端配置
        clientConfig = ClientConfig.load();

        // 1. 客户端屏幕状态管理器
        clientScreenManager = new ClientScreenManager();

        // 1b. 安装选择器物品的客户端钩子（记录角点 + 打开 GUI）
        CinemaSelectorItem.CLIENT_HOOK = new ClientSelectorHook();

        // 1c. 注册"打开影院界面"按键（可在原版 选项→控制 中自定义）
        // 26.2：KeyMapping 构造时即注册进原版按键列表（选项→控制 中可见可改）
        openSettingsKey = new KeyMapping("key.cinemaforyou.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyMapping.Category.MISC);

        // 2. 注册 S2C 包接收器
        ClientNetworkHandlers.registerReceivers();

        // 3. 注册世界渲染钩子（AFTER_ENTITIES）
        ScreenRenderer.register();

        // 4. 客户端 tick：驱动播放器 tick 的兜底（音频声像/EOF/纹理释放）。
        //    真正的纹理上传节拍由 ScreenRenderer 的每渲染帧调用提供，
        //    tick() 幂等，两处同时调用无副作用。
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (clientScreenManager != null) {
                clientScreenManager.tick();
            }
            // 自定义按键（默认 V）：无 GUI 时打开影院设置/屏幕控制
            if (openSettingsKey != null && openSettingsKey.consumeClick()
                    && client.gui.screen() == null && client.player != null) {
                com.cinemaforyou.data.CinemaScreen targetScreen =
                        clientScreenManager.findScreenInSight(
                                client.player.getEyePosition(),
                                client.player.getLookAngle(),
                                16.0);
                if (targetScreen != null) {
                    client.gui.setScreen(new ScreenControlScreen(targetScreen.id()));
                } else {
                    client.gui.setScreen(new CinemaSettingsScreen());
                }
            }
        });

        // 5. 断线时清理所有 VideoPlayer / 纹理 / 音频源
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (clientScreenManager != null) {
                clientScreenManager.cleanup();
                CLIENT_LOGGER.info("[CinemaForYou] 已清理所有客户端视频资源");
            }
        });

        // 6. 首次启动异步下载 yt-dlp（不阻塞主线程，可由配置关闭）
        if (clientConfig.autoDownloadYtDlp) {
            YtDlpDownloader.ensureDownloadedAsync();
        }

        CLIENT_LOGGER.info("[CinemaForYou] 客户端初始化完成");
    }
}
