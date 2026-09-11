package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.config.ServerConfig;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.manager.MediaUploadManager;
import com.cinemaforyou.manager.ScreenManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * 公共端网络注册（服务端为主）。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link #registerPayloads()} - 注册 S2C/C2S 包的 codec（两端均需调用，本类在
 *       {@link CinemaForYou#onInitialize()} 中调用）</li>
 *   <li>{@link #registerJoinHandler()} - 注册玩家加入事件，发送全量屏幕同步</li>
 *   <li>{@link #registerServerReceivers()} - 注册服务端 C2S 包接收器</li>
 * </ul>
 *
 * <p>客户端 S2C 包接收器见
 * {@link com.cinemaforyou.client.network.ClientNetworkHandlers}（客户端源集）。
 */
public final class NetworkHandlers {

    private NetworkHandlers() {}

    /** 注册所有自定义包的 codec（S2C + C2S）。必须在两端都调用。 */
    public static void registerPayloads() {
        // S2C（服务端发 → 客户端收）
        PayloadTypeRegistry.clientboundPlay().register(ScreenSyncPayload.TYPE, ScreenSyncPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ScreenStatePayload.TYPE, ScreenStatePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MediaListPayload.TYPE, MediaListPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ScreenMetaPayload.TYPE, ScreenMetaPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlayLogPayload.TYPE, PlayLogPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MediaMetaPayload.TYPE, MediaMetaPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ScreenQueuePayload.TYPE, ScreenQueuePayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(
                MediaUploadStatusPayload.TYPE, MediaUploadStatusPayload.STREAM_CODEC);

        // C2S（客户端发 → 服务端收）
        PayloadTypeRegistry.serverboundPlay().register(ScreenActionPayload.TYPE, ScreenActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CreateScreenPayload.TYPE, CreateScreenPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                UpdateScreenSettingsPayload.TYPE, UpdateScreenSettingsPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                RequestMediaListPayload.TYPE, RequestMediaListPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ScreenMovePayload.TYPE, ScreenMovePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ScreenResizePayload.TYPE, ScreenResizePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ScreenAdminActionPayload.TYPE, ScreenAdminActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ScreenMetaRequestPayload.TYPE, ScreenMetaRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                PlayLogRequestPayload.TYPE, PlayLogRequestPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                PlayLogActionPayload.TYPE, PlayLogActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                MediaDeletePayload.TYPE, MediaDeletePayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ScreenQueueActionPayload.TYPE, ScreenQueueActionPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                QueueUploadPayload.TYPE, QueueUploadPayload.STREAM_CODEC);
        // 本地上传到服务器媒体库（分块传输：开始 / 分块 / 结束 / 取消）
        PayloadTypeRegistry.serverboundPlay().register(
                MediaUploadStartPayload.TYPE, MediaUploadStartPayload.STREAM_CODEC);
        // 分块包单包约 256KB，远超原版 32767 字节上限：必须 registerLarge
        // 交给 Fabric 自动拆包/合包，否则发送大块会直接断开连接
        PayloadTypeRegistry.serverboundPlay().registerLarge(
                MediaUploadChunkPayload.TYPE, MediaUploadChunkPayload.STREAM_CODEC,
                MediaUploadChunkPayload.MAX_WIRE_BYTES);
        PayloadTypeRegistry.serverboundPlay().register(
                MediaUploadFinishPayload.TYPE, MediaUploadFinishPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                MediaUploadCancelPayload.TYPE, MediaUploadCancelPayload.STREAM_CODEC);

        // 注册服务端 C2S 接收器
        registerServerReceivers();

        CinemaForYou.LOGGER.info("[CinemaForYou] 网络包 codec 已注册");
    }

    /** 服务端注册 C2S 包接收器。 */
    private static void registerServerReceivers() {
        // RequestMediaListPayload：客户端请求服务器媒体库 → 回复文件列表
        ServerPlayNetworking.registerGlobalReceiver(RequestMediaListPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        if (ServerPlayNetworking.canSend(player, MediaListPayload.TYPE)) {
                            ServerPlayNetworking.send(player, new MediaListPayload(
                                    com.cinemaforyou.manager.MediaHttpServer.listMediaFiles()));
                        }
                        if (ServerPlayNetworking.canSend(player, MediaMetaPayload.TYPE)) {
                            ScreenManager mgr = CinemaForYou.screenManager;
                            java.io.File dir = com.cinemaforyou.manager.MediaHttpServer.mediaDirectory();
                            java.util.List<MediaMetaPayload.Entry> metas = new java.util.ArrayList<>();
                            for (String name : com.cinemaforyou.manager.MediaHttpServer.listMediaFiles()) {
                                java.io.File f = new java.io.File(dir, name);
                                String owner = (mgr != null) ? mgr.mediaOwnerOf(name) : "";
                                metas.add(new MediaMetaPayload.Entry(
                                        name, owner, f.isFile() ? f.lastModified() : 0L));
                            }
                            ServerPlayNetworking.send(player, new MediaMetaPayload(metas));
                        }
                    });
                });

        // ScreenActionPayload（播放/暂停/停止/跳转）
        ServerPlayNetworking.registerGlobalReceiver(ScreenActionPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> handleAction(payload, player, server));
                });

        // CreateScreenPayload（客户端选完角点后创建屏幕）
        ServerPlayNetworking.registerGlobalReceiver(CreateScreenPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) {
                            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
                            return;
                        }
                        // 配置：是否仅 op 可创建
                        ServerConfig cfg = CinemaForYou.serverConfig;
                        if (cfg != null && cfg.requireOpForCreate) {
                            boolean isOp = player.createCommandSourceStack().permissions().hasPermission(
                                    new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
                            if (!isOp) {
                                player.sendSystemMessage(Component.literal(
                                        "§c[CinemaForYou] 服务端已设置仅管理员可创建屏幕"));
                                return;
                            }
                        }
                        mgr.create(payload.corner1(), payload.corner2(), player,
                                payload.customId());
                        // 立即设置 URL 并播放
                        if (!payload.sourceUrl().isEmpty()) {
                            // 取刚创建的屏幕 ID（create 返回值或最近一个）
                            com.cinemaforyou.data.CinemaScreen created = mgr.findRecentByOwner(player);
                            if (created != null) {
                                mgr.play(created.id(), payload.sourceUrl(), player);
                            }
                        }
                    });
                });

        ServerPlayNetworking.registerGlobalReceiver(UpdateScreenSettingsPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> handleSettingsUpdate(payload, player));
                });

        // ScreenMovePayload：平移屏幕（上下左右，1/10 格）
        ServerPlayNetworking.registerGlobalReceiver(ScreenMovePayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) {
                            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
                            return;
                        }
                        CinemaScreen scr = mgr.get(payload.id());
                        if (scr == null) {
                            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 未找到该屏幕"));
                            return;
                        }
                        if (!canControl(scr, player)) {
                            player.sendSystemMessage(Component.literal(
                                    "§c[CinemaForYou] 你没有控制此屏幕的权限（仅 owner 或管理员）"));
                            return;
                        }
                        mgr.moveBy(payload.id(), payload.dx(), payload.dy(), payload.dz(), player);
                    });
                });

        // ScreenResizePayload：单边拉缩（四条边独立）
        ServerPlayNetworking.registerGlobalReceiver(ScreenResizePayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) {
                            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
                            return;
                        }
                        CinemaScreen scr = mgr.get(payload.id());
                        if (scr == null) {
                            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 未找到该屏幕"));
                            return;
                        }
                        if (!canControl(scr, player)) {
                            player.sendSystemMessage(Component.literal(
                                    "§c[CinemaForYou] 你没有控制此屏幕的权限（仅 owner 或管理员）"));
                            return;
                        }
                        mgr.resizeBy(payload.id(),
                                payload.c1dx(), payload.c1dy(), payload.c1dz(),
                                payload.c2dx(), payload.c2dy(), payload.c2dz(),
                                player);
                    });
                });

        // ScreenMetaRequestPayload：客户端打开管理列表 → 回发元数据
        ServerPlayNetworking.registerGlobalReceiver(ScreenMetaRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) return;
                        if (ServerPlayNetworking.canSend(player, ScreenMetaPayload.TYPE)) {
                            ServerPlayNetworking.send(player,
                                    new ScreenMetaPayload(mgr.adminMeta()));
                        }
                    });
                });

        // ScreenAdminActionPayload：改名/描述/删除（仅创建者可操作，服务端校验）
        ServerPlayNetworking.registerGlobalReceiver(ScreenAdminActionPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) {
                            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
                            return;
                        }
                        switch (payload.action()) {
                            case ScreenAdminActionPayload.ACTION_RENAME ->
                                    mgr.renameScreen(payload.id(), payload.text(), player);
                            case ScreenAdminActionPayload.ACTION_SET_DESC ->
                                    mgr.setScreenDesc(payload.id(), payload.text(), player);
                            case ScreenAdminActionPayload.ACTION_DELETE ->
                                    mgr.deleteOwned(payload.id(), player);
                            case ScreenAdminActionPayload.ACTION_DELETE_ALL_MINE ->
                                    mgr.deleteAllOwned(player);
                            default -> { }
                        }
                        // 操作后即时回发最新元数据（客户端刷新列表）
                        if (ServerPlayNetworking.canSend(player, ScreenMetaPayload.TYPE)) {
                            ServerPlayNetworking.send(player,
                                    new ScreenMetaPayload(mgr.adminMeta()));
                        }
                    });
                });

        // PlayLogRequestPayload：客户端打开播放历史 → 回发日志
        ServerPlayNetworking.registerGlobalReceiver(PlayLogRequestPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) return;
                        if (ServerPlayNetworking.canSend(player, PlayLogPayload.TYPE)) {
                            ServerPlayNetworking.send(player, buildPlayLog(mgr));
                        }
                    });
                });

        // PlayLogActionPayload：删除单条/清空本人历史
        ServerPlayNetworking.registerGlobalReceiver(PlayLogActionPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) return;
                        if (payload.action() == PlayLogActionPayload.ACTION_DELETE) {
                            mgr.deleteLogEntry(payload.entryId(), player);
                        } else {
                            mgr.clearMyLog(player);
                        }
                        if (ServerPlayNetworking.canSend(player, PlayLogPayload.TYPE)) {
                            ServerPlayNetworking.send(player, buildPlayLog(mgr));
                        }
                    });
                });

        // MediaDeletePayload：删除服务器媒体文件（删除的是服务器磁盘真实文件，仅管理员可操作）
        ServerPlayNetworking.registerGlobalReceiver(MediaDeletePayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        if (!player.createCommandSourceStack().permissions().hasPermission(
                                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS))) {
                            player.sendSystemMessage(Component.literal(
                                    "§c[CinemaForYou] 只有管理员可以删除服务器媒体文件"));
                            return;
                        }
                        String name = payload.name() == null ? "" : payload.name();
                        try {
                            // 支持"玩家名/文件"子目录相对路径；非法/越界（含上传临时目录）一律拒绝
                            java.io.File target =
                                    com.cinemaforyou.manager.MediaHttpServer.resolveMediaFile(name);
                            if (target == null) {
                                player.sendSystemMessage(Component.literal(
                                        "§c[CinemaForYou] 非法的文件名"));
                                return;
                            }
                            if (!target.isFile()) {
                                player.sendSystemMessage(Component.literal(
                                        "§c[CinemaForYou] 文件不存在: " + name));
                                return;
                            }
                            if (target.delete()) {
                                ScreenManager mgr = CinemaForYou.screenManager;
                                if (mgr != null) {
                                    String key = com.cinemaforyou.manager.MediaHttpServer
                                            .relativeMediaPath(target);
                                    mgr.forgetMedia(key == null ? name : key);
                                }
                                player.sendSystemMessage(Component.literal(
                                        "§c[CinemaForYou] 已删除服务器文件: " + name
                                                + "（磁盘上的真实文件，不可恢复）"));
                            } else {
                                player.sendSystemMessage(Component.literal(
                                        "§c[CinemaForYou] 删除失败（文件可能被占用）: " + name));
                            }
                        } catch (Throwable t) {
                            player.sendSystemMessage(Component.literal(
                                    "§c[CinemaForYou] 删除出错: " + t));
                        }
                    });
                });

        // ScreenQueueActionPayload：队列增/删/清空/上移下移/从队列播放（服务端校验权限）
        ServerPlayNetworking.registerGlobalReceiver(ScreenQueueActionPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> handleQueueAction(payload, player));
                });

        // QueueUploadPayload：旧版本机队列一次性迁移上传
        ServerPlayNetworking.registerGlobalReceiver(QueueUploadPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> {
                        ScreenManager mgr = CinemaForYou.screenManager;
                        if (mgr == null) return;
                        mgr.queueUpload(player, payload.entries());
                    });
                });

        // ───────────── 本地视频上传到服务器媒体库（分块传输，仅 OP≥2） ─────────────

        // MediaUploadStartPayload：开始上传（校验权限/扩展名/重名，回 READY + 最终文件名）
        ServerPlayNetworking.registerGlobalReceiver(MediaUploadStartPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> MediaUploadManager.handleStart(
                            player, payload.uploadId(), payload.fileName(), payload.fileSize()));
                });

        // MediaUploadChunkPayload：单个分块数据（追加写入临时文件，绝不整文件单包）
        ServerPlayNetworking.registerGlobalReceiver(MediaUploadChunkPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> MediaUploadManager.handleChunk(
                            player, payload.uploadId(), payload.data()));
                });

        // MediaUploadFinishPayload：分块发完（校验字节数一致后落盘进媒体目录）
        ServerPlayNetworking.registerGlobalReceiver(MediaUploadFinishPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> MediaUploadManager.handleFinish(
                            player, payload.uploadId()));
                });

        // MediaUploadCancelPayload：取消上传（删除临时分块文件）
        ServerPlayNetworking.registerGlobalReceiver(MediaUploadCancelPayload.TYPE,
                (payload, context) -> {
                    ServerPlayer player = context.player();
                    MinecraftServer server = context.server();
                    server.execute(() -> MediaUploadManager.handleCancel(
                            player, payload.uploadId()));
                });

        // 玩家断线：丢弃未完成的上传会话（清理临时分块文件与重名占位）
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                MediaUploadManager.onPlayerDisconnect(handler.getPlayer().getUUID()));
    }

    /** 处理客户端发来的队列操作请求。 */
    private static void handleQueueAction(ScreenQueueActionPayload payload, ServerPlayer player) {
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) {
            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
            return;
        }
        switch (payload.action()) {
            case ScreenQueueActionPayload.ACTION_ADD ->
                    mgr.queueAdd(payload.id(), payload.url(), player);
            case ScreenQueueActionPayload.ACTION_ADD_GLOBAL ->
                    mgr.globalQueueAdd(payload.url(), player);
            case ScreenQueueActionPayload.ACTION_REMOVE ->
                    mgr.queueRemove(payload.id(), payload.index(), player);
            case ScreenQueueActionPayload.ACTION_CLEAR ->
                    mgr.queueClear(payload.id(), player);
            case ScreenQueueActionPayload.ACTION_MOVE ->
                    mgr.queueMove(payload.id(), payload.index(), payload.toIndex(), player);
            case ScreenQueueActionPayload.ACTION_PLAY ->
                    mgr.queuePlay(payload.id(), payload.index(), player);
            case ScreenQueueActionPayload.ACTION_GLOBAL_REMOVE ->
                    mgr.globalQueueRemove(payload.index(), player);
            case ScreenQueueActionPayload.ACTION_GLOBAL_CLEAR ->
                    mgr.globalQueueClear(player);
            case ScreenQueueActionPayload.ACTION_GLOBAL_MOVE ->
                    mgr.globalQueueMove(payload.index(), payload.toIndex(), player);
            case ScreenQueueActionPayload.ACTION_GLOBAL_PLAY ->
                    mgr.globalQueuePlay(payload.index(), player);
            default -> { }
        }
    }

    /** 构造并广播全部屏幕队列（队列变更后由 ScreenManager 调用）。 */
    public static void broadcastQueues(List<com.cinemaforyou.network.QueueEntry> entries) {
        ScreenQueuePayload payload = new ScreenQueuePayload(entries);
        for (ServerPlayer player : getServer().getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, ScreenQueuePayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /** 处理客户端发来的动作请求。 */
    private static void handleAction(ScreenActionPayload payload, ServerPlayer player, MinecraftServer server) {
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) {
            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
            return;
        }

        UUID id = payload.id();
        // 广播播放（总设置入口）不针对单个屏幕：服务端向各屏 owner 发播放申请（ScreenManager.playAll）
        if (payload.action() == ScreenActionPayload.Action.PLAY_ALL) {
            mgr.playAll(payload.sourceUrl(), player);
            return;
        }
        // 权限校验：屏幕 owner 或 op2(GAMEMASTERS) 可控制
        CinemaScreen screen = mgr.get(id);
        if (screen != null && !canControl(screen, player)) {
            // 播放类改为申请制：对没有控制权的屏幕发起播放不再直接拒绝，
            // 而是向该屏 owner 发送同款播放申请（owner 接受后才播放）
            if (payload.action() == ScreenActionPayload.Action.PLAY) {
                mgr.requestPlayOnScreen(id, payload.sourceUrl(), -1, player);
                return;
            }
            // 失败上报无权限时静默忽略，避免骚扰非 owner 观看者
            if (payload.action() == ScreenActionPayload.Action.REPORT_ERROR) return;
            player.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 你没有控制此屏幕的权限（仅 owner 或管理员）"));
            return;
        }

        switch (payload.action()) {
            case PLAY -> mgr.play(id, payload.sourceUrl(), player);
            case PAUSE -> mgr.pause(id, player);
            case RESUME -> mgr.resume(id, player);
            case STOP -> mgr.stop(id, player);
            case SEEK -> mgr.seek(id, payload.param(), player);
            // 客户端解析/解码失败：把状态拉回 STOPPED 并广播，
            // 避免状态永远卡在"正在播放"而画面全黑
            case REPORT_ERROR -> {
                player.sendSystemMessage(Component.literal(
                        "§e⏹ 已因播放失败停止该屏幕"));
                mgr.stop(id, player);
            }
            default -> { }
        }
    }

    /** 处理屏幕设置更新请求。 */
    private static void handleSettingsUpdate(UpdateScreenSettingsPayload payload, ServerPlayer player) {
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) {
            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
            return;
        }
        CinemaScreen screen = mgr.get(payload.id());
        if (screen == null) {
            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 未找到该屏幕"));
            return;
        }
        if (!canControl(screen, player)) {
            player.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 你没有控制此屏幕的权限（仅 owner 或管理员）"));
            return;
        }
        mgr.updateSettings(payload.id(),
                payload.brightnessPercent(),
                payload.volumePercent(),
                payload.resolutionHeight(),
                payload.displayScalePercent(),
                payload.audioRangeBlocks(),
                payload.audioFalloffTenths(),
                payload.curvatureType(),
                payload.curvDegL(),
                payload.curvDegR(),
                payload.curvDegT(),
                payload.curvDegB(),
                payload.tiltDegH(),
                payload.tiltDegV(),
                player);
    }

    /** 构造播放历史 S2C 负载（最新在前）。 */
    private static PlayLogPayload buildPlayLog(ScreenManager mgr) {
        java.util.List<PlayLogPayload.Entry> out = new java.util.ArrayList<>();
        for (ScreenManager.LogEntry e : mgr.playLogEntries()) {
            out.add(new PlayLogPayload.Entry(e.id, e.timeMs, e.playerUuid, e.playerName, e.url));
        }
        return new PlayLogPayload(out);
    }

    /** 判断玩家是否有权控制某屏幕：owner 或 op 等级 ≥ 2（实现见 ScreenManager，与队列操作同一套校验）。 */
    private static boolean canControl(CinemaScreen screen, ServerPlayer player) {
        return ScreenManager.canControl(screen, player);
    }

    /** 注册玩家加入事件：发送全量屏幕列表。在 SERVER_STARTED 时调用。 */
    public static void registerJoinHandler() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ScreenManager mgr = CinemaForYou.screenManager;
            if (mgr == null) return;
            // 延迟 1 tick 发送，确保客户端已就绪
            server.executeIfPossible(() -> {
                List<CinemaScreen> all = mgr.allScreens();
                if (!all.isEmpty()) {
                    sender.sendPacket(new ScreenSyncPayload(all));
                    // 同时发送每个屏幕的当前状态
                    mgr.sendAllStates(handler.getPlayer(), sender);
                }
                // 队列全量同步（即使没有屏幕也发空列表：客户端据此确认服务端支持队列，
                // 并触发旧版本机队列的一次性迁移上传）
                if (ServerPlayNetworking.canSend(handler.getPlayer(), ScreenQueuePayload.TYPE)) {
                    sender.sendPacket(new ScreenQueuePayload(mgr.allQueueEntries()));
                }
            });
        });
    }

    /** 向所有在线玩家广播全量屏幕列表。 */
    public static void broadcastSync(List<CinemaScreen> screens) {
        ScreenSyncPayload payload = new ScreenSyncPayload(screens);
        for (ServerPlayer player : getServer().getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, ScreenSyncPayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /** 向所有在线玩家广播单屏状态变更。 */
    public static void broadcastState(ScreenStatePayload payload) {
        for (ServerPlayer player : getServer().getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(player, ScreenStatePayload.TYPE)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static MinecraftServer getServer() {
        return CinemaForYou.screenManager.getServer();
    }
}
