package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：上传结束（所有分块已发送）。
 *
 * <p>服务端校验接收字节数与声明大小一致后，把临时文件落盘为最终文件名，
 * 回 {@link MediaUploadStatusPayload}（SUCCESS/ERROR）。
 */
public record MediaUploadFinishPayload(int uploadId) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_upload_finish");

    public static final CustomPacketPayload.Type<MediaUploadFinishPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaUploadFinishPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaUploadFinishPayload decode(RegistryFriendlyByteBuf buf) {
                    return new MediaUploadFinishPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaUploadFinishPayload p) {
                    buf.writeVarInt(p.uploadId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
