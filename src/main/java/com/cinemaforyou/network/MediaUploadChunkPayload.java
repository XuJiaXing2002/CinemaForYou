package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：上传分块数据（每块约 256KB，见客户端 {@code MediaUploader.CHUNK_SIZE}）。
 *
 * <p>服务端按 {@code uploadId} 找到该玩家的上传会话，顺序追加写入临时文件；
 * 单块过大（超过服务端限定的 512KB）会被拒绝，避免个别客户端一次性塞爆连接。
 */
public record MediaUploadChunkPayload(int uploadId, byte[] data) implements CustomPacketPayload {

    /**
     * 注册用的单包最大字节数（含标识符等头部开销）。
     *
     * <p>原版自定义包上限仅 32767 字节，必须用 Fabric 的
     * {@code PayloadTypeRegistry.registerLarge} 注册才会自动拆包/合包，
     * 否则 256KB 分块会直接把连接踢掉（"may not be larger than" 异常）。
     */
    public static final int MAX_WIRE_BYTES = 512 * 1024;

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_upload_chunk");

    public static final CustomPacketPayload.Type<MediaUploadChunkPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaUploadChunkPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaUploadChunkPayload decode(RegistryFriendlyByteBuf buf) {
                    int id = buf.readVarInt();
                    byte[] data = buf.readByteArray();
                    return new MediaUploadChunkPayload(id, data == null ? new byte[0] : data);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaUploadChunkPayload p) {
                    buf.writeVarInt(p.uploadId());
                    buf.writeByteArray(p.data() == null ? new byte[0] : p.data());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
