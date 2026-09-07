package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C2S 屏幕管理操作：action 0=改名 1=设置描述 2=删除单个 3=删除自己创建的全部。
 * 仅该屏幕创建者可执行（服务端校验）。
 */
public record ScreenAdminActionPayload(
        int action,
        UUID id,
        String text
) implements CustomPacketPayload {

    public static final int ACTION_RENAME = 0;
    public static final int ACTION_SET_DESC = 1;
    public static final int ACTION_DELETE = 2;
    public static final int ACTION_DELETE_ALL_MINE = 3;

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_admin_action");

    public static final CustomPacketPayload.Type<ScreenAdminActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenAdminActionPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ScreenAdminActionPayload decode(RegistryFriendlyByteBuf buf) {
                    return new ScreenAdminActionPayload(buf.readVarInt(), buf.readUUID(), buf.readUtf());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScreenAdminActionPayload p) {
                    buf.writeVarInt(p.action());
                    buf.writeUUID(p.id());
                    buf.writeUtf(p.text() == null ? "" : p.text());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
