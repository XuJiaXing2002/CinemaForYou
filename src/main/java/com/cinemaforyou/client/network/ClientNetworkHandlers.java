package com.cinemaforyou.client.network;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.network.CreateScreenPayload;
import com.cinemaforyou.network.MediaListPayload;
import com.cinemaforyou.network.RequestMediaListPayload;
import com.cinemaforyou.network.ScreenActionPayload;
import com.cinemaforyou.network.ScreenStatePayload;
import com.cinemaforyou.network.ScreenSyncPayload;
import com.cinemaforyou.network.UpdateScreenSettingsPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * 客户端网络包接收器注册。
 *
 * <p>引用了 {@link ClientPlayNetworking} 等仅客户端可用的 API，
 * 由 {@link Environment} 注解保证服务端不会加载此类。
 */
@Environment(EnvType.CLIENT)
public final class ClientNetworkHandlers {

    private ClientNetworkHandlers() {}

    /** 注册所有 S2C 包的客户端接收器。在 {@link CinemaForYouClient#onInitializeClient()} 中调用。 */
    public static void registerReceivers() {
        // 全量屏幕列表（玩家加入时收到）
        ClientPlayNetworking.registerGlobalReceiver(ScreenSyncPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        CinemaForYouClient.clientScreenManager.handleSync(payload)));

        // 单屏状态变更（播放/暂停/停止/跳转）
        ClientPlayNetworking.registerGlobalReceiver(ScreenStatePayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        CinemaForYouClient.clientScreenManager.handleStateChange(payload)));

        // 服务器媒体库文件列表
        ClientPlayNetworking.registerGlobalReceiver(MediaListPayload.TYPE, (payload, context) ->
                context.client().execute(() ->
                        com.cinemaforyou.client.network.MediaLibraryClient.accept(payload.files())));
    }

    /** 向服务端请求"服务器媒体库"文件列表。 */
    public static void sendMediaListRequest() {
        ClientPlayNetworking.send(new RequestMediaListPayload());
    }

    /** 向服务端发送客户端动作（播放/暂停/停止/跳转）。 */
    public static void sendAction(ScreenActionPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    /** 向服务端发送创建屏幕请求（选完角点 + URL 后调用）。 */
    public static void sendCreateScreen(CreateScreenPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    /** 向服务端发送屏幕设置更新。 */
    public static void sendScreenSettings(UpdateScreenSettingsPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    /** 向服务端发送屏幕平移请求（dx/dy/dz 世界坐标格数）。 */
    public static void sendMove(java.util.UUID screenId, int dx, int dy, int dz) {
        ClientPlayNetworking.send(com.cinemaforyou.network.ScreenMovePayload.move(screenId, dx, dy, dz));
    }

    /** 向服务端发送单边拉缩请求（两个角点各自的世界偏移）。 */
    public static void sendResize(java.util.UUID screenId,
                                  int c1dx, int c1dy, int c1dz,
                                  int c2dx, int c2dy, int c2dz) {
        ClientPlayNetworking.send(new com.cinemaforyou.network.ScreenResizePayload(
                screenId, c1dx, c1dy, c1dz, c2dx, c2dy, c2dz));
    }
}
