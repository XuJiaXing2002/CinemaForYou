package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C2S 客户端动作包。
 *
 * <p>客户端通过此包请求服务端变更某屏幕状态。服务端验证权限后
 * 修改 {@link com.cinemaforyou.manager.ScreenManager} 并广播
 * {@link ScreenStatePayload} 给所有玩家。
 *
 * <p>支持的 action：
 * <ul>
 *   <li>{@link Action#PLAY} - 用 {@code sourceUrl} 携带视频地址（作用于该屏）；
 *       对没有控制权的屏幕发起播放时服务端不直接拒绝，而是向该屏 owner 发送
 *       与 {@link Action#PLAY_ALL} 同款的播放申请（owner 接受后才播放）；</li>
 *   <li>{@link Action#PLAY_ALL} - 广播播放（总设置入口）：不直接播放，而是由服务端向每个屏幕的
 *       owner 发送"播放申请"（聊天栏可点击接受/拒绝，60 秒超时），接受后才在该屏播放
 *       （id 不使用；任何屏幕忙碌都不影响下发）；</li>
 *   <li>{@link Action#PAUSE}</li>
 *   <li>{@link Action#RESUME}</li>
 *   <li>{@link Action#STOP}</li>
 *   <li>{@link Action#SEEK} - {@code param} 为目标毫秒位置</li>
 * </ul>
 */
public record ScreenActionPayload(
        UUID id,
        Action action,
        String sourceUrl,
        long param
) implements CustomPacketPayload {

    public enum Action {
            PLAY, PAUSE, RESUME, STOP, SEEK, REPORT_ERROR, PLAY_ALL
        }

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_action");

    public static final CustomPacketPayload.Type<ScreenActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC,
                    ScreenActionPayload::id,
                    net.minecraft.network.codec.ByteBufCodecs.idMapper(
                            i -> Action.values()[i], Action::ordinal),
                    ScreenActionPayload::action,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8,
                    ScreenActionPayload::sourceUrl,
                    net.minecraft.network.codec.ByteBufCodecs.LONG,
                    ScreenActionPayload::param,
                    ScreenActionPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── 工厂方法 ──

    /** 播放到指定屏幕（屏幕控制页等"本屏自身"操作）。 */
    public static ScreenActionPayload play(UUID id, String url) {
        return new ScreenActionPayload(id, Action.PLAY, url, 0L);
    }

    /** 广播播放：向所有屏幕的 owner 发送播放申请（总设置入口，service 端逐屏派发申请）。 */
    public static ScreenActionPayload playAll(String url) {
        return new ScreenActionPayload(new UUID(0L, 0L), Action.PLAY_ALL, url, 0L);
    }

    public static ScreenActionPayload pause(UUID id) {
        return new ScreenActionPayload(id, Action.PAUSE, "", 0L);
    }

    public static ScreenActionPayload resume(UUID id) {
        return new ScreenActionPayload(id, Action.RESUME, "", 0L);
    }

    public static ScreenActionPayload stop(UUID id) {
        return new ScreenActionPayload(id, Action.STOP, "", 0L);
    }

    public static ScreenActionPayload seek(UUID id, long positionMs) {
        return new ScreenActionPayload(id, Action.SEEK, "", positionMs);
    }

    /** 客户端解码/解析失败上报：服务端将屏幕状态拉回 STOPPED 并广播。 */
    public static ScreenActionPayload reportError(UUID id, String errorMessage) {
        return new ScreenActionPayload(id, Action.REPORT_ERROR, errorMessage, 0L);
    }
}
