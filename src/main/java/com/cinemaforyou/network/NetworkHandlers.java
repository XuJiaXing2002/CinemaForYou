package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.config.ServerConfig;
import com.cinemaforyou.data.CinemaScreen;
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
    }

    /** 处理客户端发来的动作请求。 */
    private static void handleAction(ScreenActionPayload payload, ServerPlayer player, MinecraftServer server) {
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) {
            player.sendSystemMessage(Component.literal("§c[CinemaForYou] 屏幕管理器未就绪"));
            return;
        }

        UUID id = payload.id();
        // 权限校验：屏幕 owner 或 op2(GAMEMASTERS) 可控制
        CinemaScreen screen = mgr.get(id);
        if (screen != null && !canControl(screen, player)) {
            // 失败上报无权限时静默忽略，避免骚扰非 owner 观看者
            if (payload.action() == ScreenActionPayload.Action.REPORT_ERROR) return;
            player.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 你没有控制此屏幕的权限（仅 owner 或管理员）"));
            return;
        }

        switch (payload.action()) {
            case PLAY -> {
                mgr.play(id, payload.sourceUrl(), player);
            }
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

    /** 判断玩家是否有权控制某屏幕：owner 或 op 等级 ≥ 2（配合 LuckPerms 等分配）。 */
    private static boolean canControl(CinemaScreen screen, ServerPlayer player) {
        if (screen.ownerId().equals(player.getUUID().toString())) {
            return true;
        }
        return player.createCommandSourceStack().permissions().hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
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
