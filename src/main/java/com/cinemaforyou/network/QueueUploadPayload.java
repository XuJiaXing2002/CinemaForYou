package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * C2S：旧版本机队列一次性迁移上传。
 *
 * <p>客户端首次收到 {@link ScreenQueuePayload}（说明服务端已支持服务端队列）时，
 * 若本机配置里仍有旧队列（{@code ClientConfig.screenPlaylist}），把它整体上传；
 * 服务端按权限过滤（owner 或 OP≥2）、同屏同 URL 去重后并入，并在加入时间缺失时
 * 保留客户端记录的原值。上传成功后客户端不再使用本机队列。
 */
public record QueueUploadPayload(List<QueueEntry> entries) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "queue_upload");

    public static final CustomPacketPayload.Type<QueueUploadPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, QueueUploadPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public QueueUploadPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<QueueEntry> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new QueueEntry(buf.readUUID(), buf.readUtf(),
                                buf.readUtf(), buf.readLong()));
                    }
                    return new QueueUploadPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, QueueUploadPayload p) {
                    buf.writeVarInt(p.entries().size());
                    for (QueueEntry e : p.entries()) {
                        buf.writeUUID(e.screenId());
                        buf.writeUtf(e.url() == null ? "" : e.url());
                        buf.writeUtf(e.player() == null ? "" : e.player());
                        buf.writeLong(e.timeMs());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
