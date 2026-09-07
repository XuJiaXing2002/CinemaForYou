package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S：删除服务器媒体文件（仅管理员/op，删除的是服务器磁盘上的真实文件）。 */
public record MediaDeletePayload(String name) implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "media_delete");

    public static final CustomPacketPayload.Type<MediaDeletePayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, MediaDeletePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public MediaDeletePayload decode(RegistryFriendlyByteBuf buf) {
                    return new MediaDeletePayload(buf.readUtf(512));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, MediaDeletePayload p) {
                    buf.writeUtf(p.name() == null ? "" : p.name(), 512);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
