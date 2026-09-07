package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * S2C 屏幕元数据（创建者名 + 描述）。客户端打开屏幕管理列表时请求一次。
 */
public record ScreenMetaPayload(java.util.List<Entry> entries) implements CustomPacketPayload {

    /** 单个屏幕的元数据。 */
    public record Entry(java.util.UUID id, String ownerName, String desc) {}

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_meta");

    public static final CustomPacketPayload.Type<ScreenMetaPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenMetaPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ScreenMetaPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    java.util.List<Entry> list = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new Entry(buf.readUUID(), buf.readUtf(), buf.readUtf()));
                    }
                    return new ScreenMetaPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScreenMetaPayload p) {
                    buf.writeVarInt(p.entries().size());
                    for (Entry e : p.entries()) {
                        buf.writeUUID(e.id());
                        buf.writeUtf(e.ownerName() == null ? "" : e.ownerName());
                        buf.writeUtf(e.desc() == null ? "" : e.desc());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
