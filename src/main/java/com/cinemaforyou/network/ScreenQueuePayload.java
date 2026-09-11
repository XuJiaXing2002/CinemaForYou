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
 * S2C：全部屏幕的播放队列 + 全局播放队列（服务端为唯一数据源，客户端只保留只读镜像）。
 *
 * <p>队列在服务端变更（增/删/清空/上移下移/全局队列操作/迁移上传）后全量广播；
 * 玩家加入时也会收到一次，客户端据此刷新镜像并触发旧版本机队列迁移。
 *
 * <p>全局播放队列条目（{@link QueueEntry#global()} = true，屏幕为 null）不参与自动连播，
 * 只在总设置的「全局播放队列管理」里手动点击时才向所有屏幕发播放申请。
 */
public record ScreenQueuePayload(List<QueueEntry> entries) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_queue");

    public static final CustomPacketPayload.Type<ScreenQueuePayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenQueuePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ScreenQueuePayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<QueueEntry> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        // 全局条目没有所属屏幕：只写标志位，不写 screenId
                        boolean global = buf.readBoolean();
                        java.util.UUID screenId = global ? null : buf.readUUID();
                        list.add(new QueueEntry(screenId, buf.readUtf(),
                                buf.readUtf(), buf.readLong(), global));
                    }
                    return new ScreenQueuePayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScreenQueuePayload p) {
                    buf.writeVarInt(p.entries().size());
                    for (QueueEntry e : p.entries()) {
                        boolean global = e.global();
                        buf.writeBoolean(global);
                        if (!global) {
                            buf.writeUUID(e.screenId());
                        }
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
