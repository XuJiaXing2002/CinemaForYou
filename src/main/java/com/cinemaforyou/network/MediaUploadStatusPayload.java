package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C：上传状态回执（READY 可以开始发块 / SUCCESS 完成 / ERROR 失败 / CANCELLED 已取消）。
 *
 * <p>{@code fileName} 为服务端最终确定的文件名（重名自动加序号后的结果），
 * 客户端据此提示"上传完成: xxx"并刷新媒体库列表；失败时 {@code message} 给出原因。
 */
public record MediaUploadStatusPayload(int uploadId, int status, String fileName, String message)
        implements CustomPacketPayload {

    public static final int STATUS_READY = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_ERROR = 2;
    public static final int STATUS_CANCELLED = 3;

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_upload_status");

    public static final CustomPacketPayload.Type<MediaUploadStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaUploadStatusPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaUploadStatusPayload decode(RegistryFriendlyByteBuf buf) {
                    return new MediaUploadStatusPayload(buf.readVarInt(), buf.readVarInt(),
                            buf.readUtf(512), buf.readUtf(512));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaUploadStatusPayload p) {
                    buf.writeVarInt(p.uploadId());
                    buf.writeVarInt(p.status());
                    buf.writeUtf(p.fileName() == null ? "" : p.fileName(), 512);
                    buf.writeUtf(p.message() == null ? "" : p.message(), 512);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
