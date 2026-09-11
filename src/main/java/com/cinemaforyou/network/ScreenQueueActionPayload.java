package com.cinemaforyou.network;

import com.cinemaforyou.CinemaForYou;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C2S：播放队列操作（服务端队列为唯一数据源）。
 *
 * <p>服务端校验权限后执行并全量广播最新队列（{@link ScreenQueuePayload}）。
 *
 * <p>屏幕队列操作（屏幕控制页入口，owner 或 OP≥2 可操作，"播完行为=自动播放下一个"
 * 按此顺序自动连播）：
 * <ul>
 *   <li>{@link #ACTION_ADD} - 入队（{@code url} 必填，{@code id} 为屏幕）；</li>
 *   <li>{@link #ACTION_REMOVE} - 删除（{@code index}）；</li>
 *   <li>{@link #ACTION_CLEAR} - 清空某屏队列；</li>
 *   <li>{@link #ACTION_MOVE} - 上移下移（{@code index} → {@code toIndex}）；</li>
 *   <li>{@link #ACTION_PLAY} - 立即播放队列中第 {@code index} 项（不删除该条目）；
 *       非 owner/非 OP 时改为向该屏 owner 发送同款播放申请（接受后按该条目播放）。</li>
 * </ul>
 *
 * <p>全局播放队列操作（总设置入口，仅 OP≥2；条目不参与自动连播，
 * 点击播放 = 向所有屏幕的 owner 发送播放申请）：
 * <ul>
 *   <li>{@link #ACTION_ADD_GLOBAL} - 把 URL 加入全局播放队列（id 不使用）；</li>
 *   <li>{@link #ACTION_GLOBAL_REMOVE} - 删除全局队列第 {@code index} 项；</li>
 *   <li>{@link #ACTION_GLOBAL_CLEAR} - 清空全局播放队列；</li>
 *   <li>{@link #ACTION_GLOBAL_MOVE} - 全局队列上移下移（{@code index} → {@code toIndex}）；</li>
 *   <li>{@link #ACTION_GLOBAL_PLAY} - 点击第 {@code index} 项：向所有屏幕发播放申请。</li>
 * </ul>
 */
public record ScreenQueueActionPayload(
        int action,
        UUID id,
        int index,
        int toIndex,
        String url
) implements CustomPacketPayload {

    public static final int ACTION_ADD = 0;
    /** 总设置入口：把 URL 加入全局播放队列（不参与自动连播）。 */
    public static final int ACTION_ADD_GLOBAL = 1;
    public static final int ACTION_REMOVE = 2;
    public static final int ACTION_CLEAR = 3;
    public static final int ACTION_MOVE = 4;
    public static final int ACTION_PLAY = 5;
    public static final int ACTION_GLOBAL_REMOVE = 6;
    public static final int ACTION_GLOBAL_CLEAR = 7;
    public static final int ACTION_GLOBAL_MOVE = 8;
    public static final int ACTION_GLOBAL_PLAY = 9;

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(CinemaForYou.MOD_ID, "screen_queue_action");

    public static final CustomPacketPayload.Type<ScreenQueueActionPayload> TYPE =
            new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<RegistryFriendlyByteBuf, ScreenQueueActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    ScreenQueueActionPayload::action,
                    UUIDUtil.STREAM_CODEC,
                    ScreenQueueActionPayload::id,
                    ByteBufCodecs.VAR_INT,
                    ScreenQueueActionPayload::index,
                    ByteBufCodecs.VAR_INT,
                    ScreenQueueActionPayload::toIndex,
                    ByteBufCodecs.STRING_UTF8,
                    ScreenQueueActionPayload::url,
                    ScreenQueueActionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    // ── 工厂方法（屏幕队列） ──

    /** 入队到某屏（服务端记录加入者与时间）。 */
    public static ScreenQueueActionPayload add(UUID screenId, String url) {
        return new ScreenQueueActionPayload(ACTION_ADD, screenId, 0, 0, url == null ? "" : url);
    }

    public static ScreenQueueActionPayload remove(UUID screenId, int index) {
        return new ScreenQueueActionPayload(ACTION_REMOVE, screenId, index, 0, "");
    }

    public static ScreenQueueActionPayload clear(UUID screenId) {
        return new ScreenQueueActionPayload(ACTION_CLEAR, screenId, 0, 0, "");
    }

    public static ScreenQueueActionPayload move(UUID screenId, int from, int to) {
        return new ScreenQueueActionPayload(ACTION_MOVE, screenId, from, to, "");
    }

    public static ScreenQueueActionPayload play(UUID screenId, int index) {
        return new ScreenQueueActionPayload(ACTION_PLAY, screenId, index, 0, "");
    }

    // ── 工厂方法（全局播放队列） ──

    /** 总设置入口：加入全局播放队列（id 不使用）。 */
    public static ScreenQueueActionPayload addGlobal(String url) {
        return new ScreenQueueActionPayload(ACTION_ADD_GLOBAL, new UUID(0L, 0L), 0, 0,
                url == null ? "" : url);
    }

    public static ScreenQueueActionPayload globalRemove(int index) {
        return new ScreenQueueActionPayload(ACTION_GLOBAL_REMOVE, new UUID(0L, 0L), index, 0, "");
    }

    public static ScreenQueueActionPayload globalClear() {
        return new ScreenQueueActionPayload(ACTION_GLOBAL_CLEAR, new UUID(0L, 0L), 0, 0, "");
    }

    public static ScreenQueueActionPayload globalMove(int from, int to) {
        return new ScreenQueueActionPayload(ACTION_GLOBAL_MOVE, new UUID(0L, 0L), from, to, "");
    }

    /** 手动点击全局队列条目：向所有屏幕的 owner 发播放申请。 */
    public static ScreenQueueActionPayload globalPlay(int index) {
        return new ScreenQueueActionPayload(ACTION_GLOBAL_PLAY, new UUID(0L, 0L), index, 0, "");
    }
}
