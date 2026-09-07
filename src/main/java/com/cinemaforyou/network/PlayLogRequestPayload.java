package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S：请求服务器播放历史列表。 */
public record PlayLogRequestPayload() implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "play_log_request");

    public static final CustomPacketPayload.Type<PlayLogRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayLogRequestPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PlayLogRequestPayload decode(RegistryFriendlyByteBuf buf) {
                    return new PlayLogRequestPayload();
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PlayLogRequestPayload p) {
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
