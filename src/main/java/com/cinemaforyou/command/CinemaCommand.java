package com.cinemaforyou.command;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.item.ModItems;
import com.cinemaforyou.manager.ScreenManager;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.network.chat.Component;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * /cinema 命令族。
 *
 * <p>管理类子命令（give/list/delete/play/stop/pause/resume/seek）需要 OP 2 级权限。
 * {@code <id>} 支持：自定义 ID（推荐）、完整 UUID、UUID 前 8 位前缀；输入时自动补全已创建屏幕的 ID。
 *
 * <p>{@code accept/deny} 是"全局播放申请"的应答入口（聊天栏可点击文本触发）：
 * 屏幕 owner 可能不是管理员，所以这两个子命令不做 OP 限制，
 * 权限由服务端按 requestId 对应的申请（只有该屏 owner 可处理）校验。
 *
 * <ul>
 *   <li>{@code /cinema give} - 给自己一个选择器物品</li>
 *   <li>{@code /cinema list} - 列出所有屏幕</li>
 *   <li>{@code /cinema delete <id>} - 删除屏幕</li>
 *   <li>{@code /cinema play <id> <url>} - 在指定屏幕播放 URL</li>
 *   <li>{@code /cinema stop <id>} - 停止播放</li>
 *   <li>{@code /cinema pause <id>} - 暂停</li>
 *   <li>{@code /cinema resume <id>} - 恢复</li>
 *   <li>{@code /cinema seek <id> <seconds>} - 跳转</li>
 *   <li>{@code /cinema accept <requestId>} - 接受发给自己屏幕的播放申请（仅该屏 owner）</li>
 *   <li>{@code /cinema deny <requestId>} - 拒绝发给自己屏幕的播放申请（仅该屏 owner）</li>
 * </ul>
 */
public final class CinemaCommand {

    private CinemaCommand() {}

    /** {@code <id>} 参数补全：列出所有屏幕的可引用 ID（自定义名或 UUID）。 */
    private static final SuggestionProvider<CommandSourceStack> SCREEN_IDS = (ctx, builder) -> {
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr != null) {
            String remaining = builder.getRemainingLowerCase();
            for (String id : mgr.collectIdSuggestions()) {
                if (id.toLowerCase().startsWith(remaining)) {
                    builder.suggest(id);
                }
            }
        }
        return builder.buildFuture();
    };

    /** /cinema 的 LuckPerms 可分配权限节点：cinemaforyou:command。 */
    private static final net.fabricmc.fabric.api.permission.v1.PermissionNode<java.lang.Boolean> COMMAND_NODE =
            net.fabricmc.fabric.api.permission.v1.PermissionNode.of(
                    Identifier.fromNamespaceAndPath("cinemaforyou", "command"));

    /**
     * 管理类子命令的权限判定（在各自节点上生效；根节点 /cinema 不再整体要求 OP，
     * 因为非 OP 的屏幕 owner 也需要能执行 accept/deny）：
     * LuckPerms 等权限模组可授予节点 {@code cinemaforyou:command}；未授予节点时按原版 OP 2 判定。
     */
    private static boolean isAdmin(CommandSourceStack src) {
        if (src instanceof net.fabricmc.fabric.api.permission.v1.PermissionContextOwner owner) {
            Boolean hasNode = owner.checkPermission(COMMAND_NODE);
            if (Boolean.TRUE.equals(hasNode)) {
                return true;
            }
        }
        return src.permissions().hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            // 根节点不设 requires：accept/deny 要允许非 OP 的屏幕 owner 使用；
            // 各管理子命令在自身节点上单独要求 OP（isAdmin），权限不放松。
            dispatcher.register(
                    Commands.literal("cinema")
                            // /cinema give
                            .then(Commands.literal("give")
                                    .requires(CinemaCommand::isAdmin)
                                    .executes(CinemaCommand::giveSelector))
                            // /cinema list
                            .then(Commands.literal("list")
                                    .requires(CinemaCommand::isAdmin)
                                    .executes(CinemaCommand::listScreens))
                            // /cinema delete <id>
                            .then(Commands.literal("delete")
                                    .requires(CinemaCommand::isAdmin)
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::deleteScreen)))
                            // /cinema play <id> <url>
                            .then(Commands.literal("play")
                                    .requires(CinemaCommand::isAdmin)
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .then(Commands.argument("url", StringArgumentType.greedyString())
                                                    .executes(CinemaCommand::playScreen))))
                            // /cinema stop <id>
                            .then(Commands.literal("stop")
                                    .requires(CinemaCommand::isAdmin)
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::stopScreen)))
                            // /cinema pause <id>
                            .then(Commands.literal("pause")
                                    .requires(CinemaCommand::isAdmin)
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::pauseScreen)))
                            // /cinema resume <id>
                            .then(Commands.literal("resume")
                                    .requires(CinemaCommand::isAdmin)
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::resumeScreen)))
                            // /cinema seek <id> <seconds>
                            .then(Commands.literal("seek")
                                    .requires(CinemaCommand::isAdmin)
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .then(Commands.argument("seconds", LongArgumentType.longArg(0))
                                                    .executes(CinemaCommand::seekScreen))))
                            // /cinema accept <requestId>（聊天栏「接受」点击触发；仅该屏 owner 可处理）
                            .then(Commands.literal("accept")
                                    .then(Commands.argument("requestId", UuidArgument.uuid())
                                            .executes(CinemaCommand::acceptPlayRequest)))
                            // /cinema deny <requestId>（聊天栏「拒绝」点击触发；仅该屏 owner 可处理）
                            .then(Commands.literal("deny")
                                    .then(Commands.argument("requestId", UuidArgument.uuid())
                                            .executes(CinemaCommand::denyPlayRequest)))
            );
        });
        CinemaForYou.LOGGER.info("[CinemaForYou] 命令已注册");
    }

    // ───────────── 子命令实现 ─────────────

    private static int giveSelector(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ItemStack stack = new ItemStack(ModItems.CINEMA_SELECTOR);
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
        player.sendSystemMessage(Component.literal("§a[CinemaForYou] 已给你影院选择器"));
        return 1;
    }

    private static int listScreens(CommandContext<CommandSourceStack> ctx) {
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) {
            ctx.getSource().sendFailure(Component.literal("§c屏幕管理器未就绪"));
            return 0;
        }
        List<CinemaScreen> all = mgr.allScreens();
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§e当前无屏幕"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a共 " + all.size() + " 个屏幕:"), false);
        for (CinemaScreen s : all) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§7- §b" + s.displayName() + " §8(" + s.id() + ")"
                            + " §7@ " + s.corner1().toShortString() + " → " + s.corner2().toShortString()
                            + " §8[" + s.width() + "x" + s.height() + "]"
                            + (s.sourceUrl().isEmpty() ? "" : " §a" + truncate(s.sourceUrl(), 50))
            ), false);
        }
        return all.size();
    }

    private static int deleteScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) return 0;
        CinemaScreen screen = resolveScreen(ctx, mgr);
        return mgr.delete(screen.id(), player) ? 1 : 0;
    }

    private static int playScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String url = StringArgumentType.getString(ctx, "url");
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) return 0;
        CinemaScreen screen = resolveScreen(ctx, mgr);
        mgr.play(screen.id(), url, player);
        return 1;
    }

    private static int stopScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) return 0;
        CinemaScreen screen = resolveScreen(ctx, mgr);
        mgr.stop(screen.id(), player);
        return 1;
    }

    private static int pauseScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) return 0;
        CinemaScreen screen = resolveScreen(ctx, mgr);
        mgr.pause(screen.id(), player);
        return 1;
    }

    private static int resumeScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) return 0;
        CinemaScreen screen = resolveScreen(ctx, mgr);
        mgr.resume(screen.id(), player);
        return 1;
    }

    private static int seekScreen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long seconds = LongArgumentType.getLong(ctx, "seconds");
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) return 0;
        CinemaScreen screen = resolveScreen(ctx, mgr);
        mgr.seek(screen.id(), seconds * 1000, player);
        return 1;
    }

    /** 接受播放申请（聊天栏「接受」点击 → /cinema accept <requestId>）。 */
    private static int acceptPlayRequest(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return respondPlayRequest(ctx, true);
    }

    /** 拒绝播放申请（聊天栏「拒绝」点击 → /cinema deny <requestId>）。 */
    private static int denyPlayRequest(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        return respondPlayRequest(ctx, false);
    }

    /** 播放申请应答：服务端按 requestId 定位申请并校验"只有该屏 owner 可处理"。 */
    private static int respondPlayRequest(CommandContext<CommandSourceStack> ctx, boolean accept)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ScreenManager mgr = CinemaForYou.screenManager;
        if (mgr == null) {
            ctx.getSource().sendFailure(Component.literal("§c屏幕管理器未就绪"));
            return 0;
        }
        UUID requestId = UuidArgument.getUuid(ctx, "requestId");
        String error = mgr.respondPlayRequest(player, requestId, accept);
        if (error != null) {
            ctx.getSource().sendFailure(Component.literal(error));
            return 0;
        }
        return 1;
    }

    // ───────────── 辅助 ─────────────

    /**
     * 解析 {@code <id>} 参数为屏幕：自定义 ID → UUID → UUID 前缀。
     *
     * @throws CommandSyntaxException 未找到时抛出（含输入回显）
     */
    private static CinemaScreen resolveScreen(CommandContext<CommandSourceStack> ctx, ScreenManager mgr)
            throws CommandSyntaxException {
        String input = StringArgumentType.getString(ctx, "id");
        CinemaScreen screen = mgr.resolve(input);
        if (screen == null) {
            throw new CommandSyntaxException(null,
                    Component.literal("§c未找到屏幕: §e" + input + "§c（用 /cinema list 查看已有 ID）"));
        }
        return screen;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
