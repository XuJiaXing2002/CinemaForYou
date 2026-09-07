package com.cinemaforyou.item;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * 影院选择器物品 - 用法类似小木棍。
 *
 * <p>右键方块两次以确定屏幕的两个对角点。第一次右键记录 corner1，
 * 第二次右键记录 corner2 并在客户端打开 {@link com.cinemaforyou.client.gui.UrlInputScreen}。
 *
 * <p>客户端钩子模式：{@link #CLIENT_HOOK} 默认 no-op（服务端安全），
 * 客户端初始化时替换为真实实现（见 {@code ClientSelectorHook}），
 * 避免服务端意外引用客户端类。
 */
public class CinemaSelectorItem extends Item {

    /** 客户端钩子：处理右键交互（记录角点、打开 GUI）。服务端为 no-op。 */
    public static SelectorClientHook CLIENT_HOOK = (ctx, pos, isSecondClick) -> {};

    public CinemaSelectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        // 客户端：交给钩子处理（记录角点 / 打开 GUI）
        if (level.isClientSide() && player != null) {
            boolean isSecond = ClientSelectionState.isSecondClick(player.getUUID());
            CLIENT_HOOK.onUseOn(context, pos, isSecond);
            ClientSelectionState.recordClick(player.getUUID(), pos);
        }

        // 服务端：允许交互（实际屏幕创建由 CreateScreenPayload 触发）
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                 TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("item.cinemaforyou.cinema_selector.tooltip.1"));
        tooltip.accept(Component.translatable("item.cinemaforyou.cinema_selector.tooltip.2"));
    }

    // ───────────── 客户端钩子接口 ─────────────

    /** 客户端右键钩子。 */
    @FunctionalInterface
    public interface SelectorClientHook {
        /**
         * @param context        右键上下文
         * @param clickedPos     被点击的方块坐标
         * @param isSecondClick  是否为第二次点击（即 corner2）
         */
        void onUseOn(UseOnContext context, BlockPos clickedPos, boolean isSecondClick);
    }

    // ───────────── 客户端选择状态（仅客户端使用） ─────────────

    /**
     * 客户端角点选择状态。注意：此类是静态的，仅用于客户端临时存储，
     * 不参与网络传输。服务端不读取此状态。
     */
    public static final class ClientSelectionState {
        private static final java.util.Map<java.util.UUID, BlockPos> firstCorner = new java.util.HashMap<>();
        private static final java.util.Map<java.util.UUID, Long> lastClickTime = new java.util.HashMap<>();

        private ClientSelectionState() {}

        public static boolean isSecondClick(java.util.UUID playerId) {
            return firstCorner.containsKey(playerId);
        }

        public static void recordClick(java.util.UUID playerId, BlockPos pos) {
            if (firstCorner.containsKey(playerId)) {
                // 这是第二次点击 - 调用者（钩子）会处理打开 GUI
                // 清除状态以便下一次选择
                firstCorner.remove(playerId);
                lastClickTime.remove(playerId);
            } else {
                // 第一次点击
                firstCorner.put(playerId, pos);
                lastClickTime.put(playerId, System.currentTimeMillis());
            }
        }

        public static BlockPos getFirstCorner(java.util.UUID playerId) {
            return firstCorner.get(playerId);
        }

        /** 清除玩家的选择状态（断线时调用）。 */
        public static void clear(java.util.UUID playerId) {
            firstCorner.remove(playerId);
            lastClickTime.remove(playerId);
        }
    }
}
