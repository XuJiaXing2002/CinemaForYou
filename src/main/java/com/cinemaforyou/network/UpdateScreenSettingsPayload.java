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
 * C2S 屏幕设置更新包。
 *
 * <p>用于更新已创建屏幕的亮度、音量、解码分辨率、显示大小，
 * 以及音频传播范围与距离衰减（0 = 跟随客户端全局默认）。
 */
public record UpdateScreenSettingsPayload(
        UUID id,
        int brightnessPercent,
        int volumePercent,
        int resolutionHeight,
        int displayScalePercent,
        int audioRangeBlocks,
        int audioFalloffTenths
) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "update_screen_settings");

    public static final CustomPacketPayload.Type<UpdateScreenSettingsPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenSettingsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC,
                    UpdateScreenSettingsPayload::id,
                    ByteBufCodecs.VAR_INT,
                    UpdateScreenSettingsPayload::brightnessPercent,
                    ByteBufCodecs.VAR_INT,
                    UpdateScreenSettingsPayload::volumePercent,
                    ByteBufCodecs.VAR_INT,
                    UpdateScreenSettingsPayload::resolutionHeight,
                    ByteBufCodecs.VAR_INT,
                    UpdateScreenSettingsPayload::displayScalePercent,
                    ByteBufCodecs.VAR_INT,
                    UpdateScreenSettingsPayload::audioRangeBlocks,
                    ByteBufCodecs.VAR_INT,
                    UpdateScreenSettingsPayload::audioFalloffTenths,
                    UpdateScreenSettingsPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
