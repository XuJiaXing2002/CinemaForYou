package com.cinemaforyou;

import com.cinemaforyou.config.ServerConfig;
import com.cinemaforyou.manager.ScreenManager;
import com.cinemaforyou.network.NetworkHandlers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CinemaForYou 模组公共端入口点。
 *
 * <p>服务端职责：屏幕定义中枢 + 命令注册 + 网络包处理 + 持久化。
 * 客户端职责见 {@link CinemaForYouClient}。
 *
 * <p>架构说明：服务端不解码视频，仅持有屏幕元数据与播放状态时钟，
 * 通过 {@link com.cinemaforyou.network.ScreenSyncPayload} 与
 * {@link com.cinemaforyou.network.ScreenStatePayload} 广播给所有客户端，
 * 客户端各自解码渲染（JavaCV/FFmpeg）。
 */
public class CinemaForYou implements ModInitializer {

    public static final String MOD_ID = "cinemaforyou";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 服务端屏幕管理器单例（在 logical server 上下文中使用）。 */
    public static ScreenManager screenManager;

    /** 服务端配置单例。 */
    public static ServerConfig serverConfig;

    @Override
    public void onInitialize() {
        LOGGER.info("[CinemaForYou] 初始化公共端（服务端入口）...");

        // 0. 加载服务端配置
        serverConfig = ServerConfig.load();

        // 1. 注册网络包 codec（S2C + C2S）
        NetworkHandlers.registerPayloads();

        // 2. 屏幕管理器在 server starting 时构造，并加载持久化数据
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            screenManager = new ScreenManager(server);
            screenManager.load();
            LOGGER.info("[CinemaForYou] 屏幕管理器已就绪，已加载 {} 个屏幕", screenManager.size());
        });

        // 3. 玩家加入时发送全量屏幕同步；并启动内置媒体 HTTP 服务（供全服拉流本地视频）
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            NetworkHandlers.registerJoinHandler();
            com.cinemaforyou.manager.MediaHttpServer.start();
        });

        // 4. 每 tick 推进屏幕时钟并在状态变化时广播（仅 dirty 时真正发包）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (screenManager != null) {
                screenManager.tick();
            }
        });

        // 5. 服务端停止时持久化并关闭媒体服务
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            com.cinemaforyou.manager.MediaHttpServer.stop();
            if (screenManager != null) {
                screenManager.save();
                LOGGER.info("[CinemaForYou] 屏幕数据已保存");
            }
        });

        // 6. 注册命令与物品（在各自的类中完成）
        com.cinemaforyou.item.ModItems.register();
        com.cinemaforyou.command.CinemaCommand.register();

        LOGGER.info("[CinemaForYou] 公共端初始化完成");
    }
}
