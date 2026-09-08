package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** S2C：服务器媒体库元数据（文件名 → 添加者/文件修改时间）。 */
public record MediaMetaPayload(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(String name, String owner, long mtimeMs) {}

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_meta");

    public static final CustomPacketPayload.Type<MediaMetaPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaMetaPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaMetaPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<Entry> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new Entry(buf.readUtf(512), buf.readUtf(64), buf.readLong()));
                    }
                    return new MediaMetaPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaMetaPayload p) {
                    buf.writeVarInt(p.entries().size());
                    for (Entry e : p.entries()) {
                        buf.writeUtf(e.name() == null ? "" : e.name(), 512);
                        buf.writeUtf(e.owner() == null ? "" : e.owner(), 64);
                        buf.writeLong(e.mtimeMs());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
