package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S 创建屏幕请求包。
 *
 * <p>客户端在玩家用选择器物品选完两个对角点、并在 {@link com.cinemaforyou.client.gui.UrlInputScreen}
 * 中输入视频源后，发送此包给服务端创建屏幕并立即播放。
 *
 * @param corner1    第一个对角点
 * @param corner2    第二个对角点
 * @param sourceUrl  视频源 URL（YouTube/Twitch/直链/本地文件路径）
 * @param customId   可选自定义短名称（空串 = 自动 UUID）
 */
public record CreateScreenPayload(
        BlockPos corner1,
        BlockPos corner2,
        String sourceUrl,
        String customId
) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "create_screen");

    public static final CustomPacketPayload.Type<CreateScreenPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, CreateScreenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC,
                    CreateScreenPayload::corner1,
                    BlockPos.STREAM_CODEC,
                    CreateScreenPayload::corner2,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8,
                    CreateScreenPayload::sourceUrl,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8,
                    CreateScreenPayload::customId,
                    CreateScreenPayload::new
            );

    /** 便捷构造（无自定义 ID）。 */
    public CreateScreenPayload(BlockPos corner1, BlockPos corner2, String sourceUrl) {
        this(corner1, corner2, sourceUrl, "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
