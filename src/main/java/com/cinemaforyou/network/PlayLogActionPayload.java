package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** C2S：播放历史管理。action 0=删除单条（仅本人） 1=清空本人记录。 */
public record PlayLogActionPayload(int action, UUID entryId) implements CustomPacketPayload {

    public static final int ACTION_DELETE = 0;
    public static final int ACTION_CLEAR_MINE = 1;

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "play_log_action");

    public static final CustomPacketPayload.Type<PlayLogActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayLogActionPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PlayLogActionPayload decode(RegistryFriendlyByteBuf buf) {
                    return new PlayLogActionPayload(buf.readVarInt(), buf.readUUID());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PlayLogActionPayload p) {
                    buf.writeVarInt(p.action());
                    buf.writeUUID(p.entryId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
