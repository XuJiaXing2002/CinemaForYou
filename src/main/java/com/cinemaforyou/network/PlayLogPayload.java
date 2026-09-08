package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** S2C：服务器全局播放历史（谁在何时播放了什么）。 */
public record PlayLogPayload(List<Entry> entries) implements CustomPacketPayload {

    /** 一条播放记录。 */
    public record Entry(UUID id, long timeMs, String playerUuid, String playerName, String url) {}

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "play_log");

    public static final CustomPacketPayload.Type<PlayLogPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayLogPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PlayLogPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<Entry> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(new Entry(buf.readUUID(), buf.readLong(),
                                buf.readUtf(), buf.readUtf(), buf.readUtf()));
                    }
                    return new PlayLogPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PlayLogPayload p) {
                    buf.writeVarInt(p.entries().size());
                    for (Entry e : p.entries()) {
                        buf.writeUUID(e.id());
                        buf.writeLong(e.timeMs());
                        buf.writeUtf(e.playerUuid() == null ? "" : e.playerUuid());
                        buf.writeUtf(e.playerName() == null ? "" : e.playerName());
                        buf.writeUtf(e.url() == null ? "" : e.url());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
