package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * C2S：请求"服务器媒体库"文件列表（服务端 cinema/videos 目录）。
 */
public record RequestMediaListPayload() implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "request_media_list");

    public static final CustomPacketPayload.Type<RequestMediaListPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMediaListPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RequestMediaListPayload decode(RegistryFriendlyByteBuf buf) {
                    return new RequestMediaListPayload();
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, RequestMediaListPayload value) {
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
