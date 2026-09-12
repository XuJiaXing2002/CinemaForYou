package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.item.CinemaSelectorItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.context.UseOnContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 选择器物品的客户端钩子实现。
 *
 * <p>在 {@link CinemaForYouClient#onInitializeClient()} 中注册到
 * {@link CinemaSelectorItem#CLIENT_HOOK}。
 *
 * <p>第一次右键方块：记录 corner1，发聊天提示。
 * 第二次右键方块：记录 corner2，打开 {@link UrlInputScreen}。
 */
@Environment(EnvType.CLIENT)
public class ClientSelectorHook implements CinemaSelectorItem.SelectorClientHook {

    @Override
    public void onUseOn(UseOnContext context, BlockPos clickedPos, boolean isSecondClick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        if (!isSecondClick) {
            // 第一次点击：角点1 已由 ClientSelectionState.recordClick 记录；
            // 开始实时范围预览（绿色盒体延伸到准星）
            com.cinemaforyou.client.render.SelectionPreview.beginSelecting(clickedPos);
            player.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] §f角点1 已选: §7" + clickedPos.toShortString()
                            + " §8(请选第二个对角点，绿框为实时预览)"));
        } else {
            // 物品的 useOn 先调 CLIENT_HOOK 再调 recordClick，因此这里仍能取到第一个角点
            BlockPos corner1 = CinemaSelectorItem.ClientSelectionState.getFirstCorner(player.getUUID());
            if (corner1 == null) {
                player.sendSystemMessage(Component.literal("§c[CinemaForYou] 找不到第一个角点，请重新选取"));
                return;
            }
            player.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] §f角点2 已选: §7" + clickedPos.toShortString()
                            + " §8→ 打开 URL 输入屏"));
            // 黄色范围框在 URL 输入屏打开期间持续显示
            com.cinemaforyou.client.render.SelectionPreview.beginConfirming(corner1, clickedPos);
            mc.executeIfPossible(() -> mc.gui.setScreen(
                    new UrlInputScreen(corner1, clickedPos)));
        }
    }
}
