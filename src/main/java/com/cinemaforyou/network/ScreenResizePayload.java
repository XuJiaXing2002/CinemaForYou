package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C2S 屏幕单边拉缩包：把已创建屏幕的某一侧（一个角或两个角）向外/向内移动，
 * 用于四条边各自独立放大缩小。
 */
public record ScreenResizePayload(
        UUID id,
        int c1dx,
        int c1dy,
        int c1dz,
        int c2dx,
        int c2dy,
        int c2dz
) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_resize");

    public static final CustomPacketPayload.Type<ScreenResizePayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenResizePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC,
                    ScreenResizePayload::id,
                    ByteBufCodecs.VAR_INT, ScreenResizePayload::c1dx,
                    ByteBufCodecs.VAR_INT, ScreenResizePayload::c1dy,
                    ByteBufCodecs.VAR_INT, ScreenResizePayload::c1dz,
                    ByteBufCodecs.VAR_INT, ScreenResizePayload::c2dx,
                    ByteBufCodecs.VAR_INT, ScreenResizePayload::c2dy,
                    ByteBufCodecs.VAR_INT, ScreenResizePayload::c2dz,
                    ScreenResizePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
