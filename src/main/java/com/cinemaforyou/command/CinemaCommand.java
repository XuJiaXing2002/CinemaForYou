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

import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.network.chat.Component;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * /cinema 命令族。
 *
 * <p>所有子命令需要 OP 2 级权限。{@code <id>} 支持：自定义 ID（推荐）、
 * 完整 UUID、UUID 前 8 位前缀；输入时自动补全已创建屏幕的 ID。
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

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            dispatcher.register(
                    Commands.literal("cinema")
                            .requires(src -> {
                                // LuckPerms 等权限模组可授予节点 cinemaforyou:command；
                                // 未授予节点时按原版 OP 2 判定
                                if (src instanceof net.fabricmc.fabric.api.permission.v1.PermissionContextOwner owner) {
                                    Boolean hasNode = owner.checkPermission(COMMAND_NODE);
                                    if (Boolean.TRUE.equals(hasNode)) {
                                        return true;
                                    }
                                }
                                return src.permissions().hasPermission(
                                        new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
                            })
                            // /cinema give
                            .then(Commands.literal("give")
                                    .executes(CinemaCommand::giveSelector))
                            // /cinema list
                            .then(Commands.literal("list")
                                    .executes(CinemaCommand::listScreens))
                            // /cinema delete <id>
                            .then(Commands.literal("delete")
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::deleteScreen)))
                            // /cinema play <id> <url>
                            .then(Commands.literal("play")
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .then(Commands.argument("url", StringArgumentType.greedyString())
                                                    .executes(CinemaCommand::playScreen))))
                            // /cinema stop <id>
                            .then(Commands.literal("stop")
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::stopScreen)))
                            // /cinema pause <id>
                            .then(Commands.literal("pause")
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::pauseScreen)))
                            // /cinema resume <id>
                            .then(Commands.literal("resume")
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .executes(CinemaCommand::resumeScreen)))
                            // /cinema seek <id> <seconds>
                            .then(Commands.literal("seek")
                                    .then(Commands.argument("id", StringArgumentType.string())
                                            .suggests(SCREEN_IDS)
                                            .then(Commands.argument("seconds", LongArgumentType.longArg(0))
                                                    .executes(CinemaCommand::seekScreen))))
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
