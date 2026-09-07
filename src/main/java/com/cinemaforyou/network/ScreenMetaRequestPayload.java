package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** C2S 请求屏幕元数据列表（创建者名 + 描述）。 */
public record ScreenMetaRequestPayload() implements CustomPacketPayload {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_meta_request");

    public static final CustomPacketPayload.Type<ScreenMetaRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenMetaRequestPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ScreenMetaRequestPayload decode(RegistryFriendlyByteBuf buf) {
                    return new ScreenMetaRequestPayload();
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScreenMetaRequestPayload p) {
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
