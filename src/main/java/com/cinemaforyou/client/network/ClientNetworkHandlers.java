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

        // 屏幕元数据（创建者名/描述）
        ClientPlayNetworking.registerGlobalReceiver(com.cinemaforyou.network.ScreenMetaPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        com.cinemaforyou.client.network.ScreenAdminClient.accept(payload.entries())));

        // 服务器播放历史
        ClientPlayNetworking.registerGlobalReceiver(com.cinemaforyou.network.PlayLogPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        com.cinemaforyou.client.network.PlayLogClient.accept(payload.entries())));

        // 服务器媒体库元数据
        ClientPlayNetworking.registerGlobalReceiver(com.cinemaforyou.network.MediaMetaPayload.TYPE,
                (payload, context) -> context.client().execute(() ->
                        com.cinemaforyou.client.network.MediaLibraryClient.acceptMeta(payload.entries())));
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

    /** 请求屏幕元数据（打开管理列表时）。 */
    public static void requestScreenMeta() {
        ClientPlayNetworking.send(new com.cinemaforyou.network.ScreenMetaRequestPayload());
    }

    /** 屏幕管理操作（改名/描述/删除；服务端校验仅创建者可执行）。 */
    public static void sendAdminAction(int action, java.util.UUID screenId, String text) {
        ClientPlayNetworking.send(new com.cinemaforyou.network.ScreenAdminActionPayload(
                action, screenId, text == null ? "" : text));
    }

    /** 请求服务器播放历史。 */
    public static void requestPlayLog() {
        ClientPlayNetworking.send(new com.cinemaforyou.network.PlayLogRequestPayload());
    }

    /** 播放历史操作（删除单条/清空本人）。 */
    public static void sendPlayLogAction(int action, java.util.UUID entryId) {
        ClientPlayNetworking.send(new com.cinemaforyou.network.PlayLogActionPayload(action, entryId));
    }

    /** 删除服务器媒体文件（管理员；删除的是服务器磁盘真实文件）。 */
    public static void sendMediaDelete(String name) {
        ClientPlayNetworking.send(new com.cinemaforyou.network.MediaDeletePayload(name));
    }
}
