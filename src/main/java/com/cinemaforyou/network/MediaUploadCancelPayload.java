package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：取消上传（用户点「取消上传」或客户端主动放弃）。
 *
 * <p>服务端删除临时分块文件并释放重名占位；已落盘的文件不受影响。
 */
public record MediaUploadCancelPayload(int uploadId) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_upload_cancel");

    public static final CustomPacketPayload.Type<MediaUploadCancelPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaUploadCancelPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaUploadCancelPayload decode(RegistryFriendlyByteBuf buf) {
                    return new MediaUploadCancelPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaUploadCancelPayload p) {
                    buf.writeVarInt(p.uploadId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
