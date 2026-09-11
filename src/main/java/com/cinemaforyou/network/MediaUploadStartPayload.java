package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：开始上传本地视频到服务器媒体库（分块传输第 1 步）。
 *
 * <p>视频文件可能非常大，绝不整文件单包发送：本包只传文件名与总大小，
 * 由服务端校验权限（仅 OP≥2）/扩展名白名单/重名后回 {@link MediaUploadStatusPayload}
 * （READY，含服务端最终确定的文件名）；随后客户端按块发
 * {@link MediaUploadChunkPayload}，全部发完再发 {@link MediaUploadFinishPayload}。
 */
public record MediaUploadStartPayload(int uploadId, String fileName, long fileSize)
        implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_upload_start");

    public static final CustomPacketPayload.Type<MediaUploadStartPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaUploadStartPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaUploadStartPayload decode(RegistryFriendlyByteBuf buf) {
                    return new MediaUploadStartPayload(buf.readVarInt(), buf.readUtf(512),
                            buf.readLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaUploadStartPayload p) {
                    buf.writeVarInt(p.uploadId());
                    buf.writeUtf(p.fileName() == null ? "" : p.fileName(), 512);
                    buf.writeLong(p.fileSize());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
