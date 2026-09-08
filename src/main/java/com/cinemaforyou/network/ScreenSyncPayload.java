package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.data.CinemaScreen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * S2C 全量屏幕列表同步包。
 *
 * <p>服务端在以下时机发送给单个玩家：
 * <ul>
 *   <li>玩家加入服务器（{@code ServerPlayConnectionEvents.JOIN}）</li>
 *   <li>屏幕被创建/删除时，广播给所有在线玩家</li>
 * </ul>
 *
 * <p>客户端收到后用其替换本地的整个屏幕缓存。
 */
public record ScreenSyncPayload(List<CinemaScreen> screens) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_sync");

    public static final CustomPacketPayload.Type<ScreenSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    CinemaScreen.LIST_STREAM_CODEC,
                    ScreenSyncPayload::screens,
                    ScreenSyncPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
