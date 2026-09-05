package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C：服务器媒体库文件列表（文件名，按名称排序）。
 */
public record MediaListPayload(List<String> files) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_list");

    public static final CustomPacketPayload.Type<MediaListPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaListPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaListPayload decode(RegistryFriendlyByteBuf buf) {
                    int n = buf.readVarInt();
                    List<String> files = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        files.add(buf.readUtf(512));
                    }
                    return new MediaListPayload(files);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaListPayload value) {
                    List<String> files = value.files();
                    buf.writeVarInt(files.size());
                    for (String f : files) {
                        buf.writeUtf(f == null ? "" : f, 512);
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
