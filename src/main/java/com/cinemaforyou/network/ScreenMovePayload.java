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
 * C2S 屏幕平移包：把已创建的屏幕沿世界坐标移动（1/10 格步进）。
 */
public record ScreenMovePayload(
        UUID id,
        int dx,
        int dy,
        int dz
) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_move");

    public static final CustomPacketPayload.Type<ScreenMovePayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenMovePayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC,
                    ScreenMovePayload::id,
                    ByteBufCodecs.VAR_INT,
                    ScreenMovePayload::dx,
                    ByteBufCodecs.VAR_INT,
                    ScreenMovePayload::dy,
                    ByteBufCodecs.VAR_INT,
                    ScreenMovePayload::dz,
                    ScreenMovePayload::new
            );

    /** 便捷构造。 */
    public static ScreenMovePayload move(UUID id, int dx, int dy, int dz) {
        return new ScreenMovePayload(id, dx, dy, dz);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
