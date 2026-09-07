package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.data.ScreenState;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * S2C 单屏状态变更包。
 *
 * <p>当服务端 {@link com.cinemaforyou.manager.ScreenManager} 改变了某屏幕的播放状态
 * （PLAYING/PAUSED/STOPPED）或时钟位置（seek）时，向所有在线玩家广播此包。
 * 客户端据此启动/暂停/停止 {@link com.cinemaforyou.client.video.VideoPlayer}。
 *
 * @param id            屏幕 UUID
 * @param state         新状态
 * @param positionMs    视频时钟位置（毫秒，PLAYING 时用作品同步基准）
 * @param sourceUrl     当前视频源（STOPPED 时为空字符串）
 * @param serverTimeMs  服务端发送此包时的 System.currentTimeMillis()，用于估算网络延迟
 */
public record ScreenStatePayload(
        UUID id,
        ScreenState state,
        long positionMs,
        String sourceUrl,
        long serverTimeMs
) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_state");

    public static final CustomPacketPayload.Type<ScreenStatePayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC,
                    ScreenStatePayload::id,
                    // 枚举按序号传输
                    net.minecraft.network.codec.ByteBufCodecs.idMapper(i -> ScreenState.values()[i], ScreenState::ordinal),
                    ScreenStatePayload::state,
                    net.minecraft.network.codec.ByteBufCodecs.LONG,
                    ScreenStatePayload::positionMs,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8,
                    ScreenStatePayload::sourceUrl,
                    net.minecraft.network.codec.ByteBufCodecs.LONG,
                    ScreenStatePayload::serverTimeMs,
                    ScreenStatePayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
