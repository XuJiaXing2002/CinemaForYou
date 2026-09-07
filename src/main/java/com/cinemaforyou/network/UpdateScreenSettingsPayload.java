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
 * <p>用于更新已创建屏幕的亮度、音量、解码分辨率、显示大小、
 * 音频传播范围与距离衰减、曲面（类型+左/右/上/下四边弧度）与倾斜（左右/上下）。
 */
public record UpdateScreenSettingsPayload(
        UUID id,
        int brightnessPercent,
        int volumePercent,
        int resolutionHeight,
        int displayScalePercent,
        int audioRangeBlocks,
        int audioFalloffTenths,
        int curvatureType,
        int curvDegL,
        int curvDegR,
        int curvDegT,
        int curvDegB,
        int tiltDegH,
        int tiltDegV
) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "update_screen_settings");

    public static final CustomPacketPayload.Type<UpdateScreenSettingsPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreenSettingsPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public UpdateScreenSettingsPayload decode(RegistryFriendlyByteBuf buf) {
                    return new UpdateScreenSettingsPayload(
                            buf.readUUID(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt(),
                            buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, UpdateScreenSettingsPayload p) {
                    buf.writeUUID(p.id());
                    buf.writeVarInt(p.brightnessPercent());
                    buf.writeVarInt(p.volumePercent());
                    buf.writeVarInt(p.resolutionHeight());
                    buf.writeVarInt(p.displayScalePercent());
                    buf.writeVarInt(p.audioRangeBlocks());
                    buf.writeVarInt(p.audioFalloffTenths());
                    buf.writeVarInt(p.curvatureType());
                    buf.writeVarInt(p.curvDegL());
                    buf.writeVarInt(p.curvDegR());
                    buf.writeVarInt(p.curvDegT());
                    buf.writeVarInt(p.curvDegB());
                    buf.writeVarInt(p.tiltDegH());
                    buf.writeVarInt(p.tiltDegV());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
