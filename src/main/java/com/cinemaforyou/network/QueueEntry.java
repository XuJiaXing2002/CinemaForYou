package com.cinemaforyou.network;

import java.util.UUID;

/**
 * 一条播放队列条目（服务端为唯一数据源）。
 *
 * <p>两种范围（由 {@code global} 区分）：
 * <ul>
 *   <li>{@code global = false}：某屏的播放队列，参与"播完行为=自动播放下一个"的自动连播；</li>
 *   <li>{@code global = true}：总设置的全局播放队列（{@code screenId} 为 null），
 *       不参与任何屏幕的自动连播，只在「全局播放队列管理」里手动点击时才向所有屏幕发播放申请。</li>
 * </ul>
 *
 * @param screenId 所属屏幕 UUID（全局条目为 null）
 * @param url      视频源 URL
 * @param player   加入队列的玩家名（旧版本机队列迁移时可能为空）
 * @param timeMs   加入时间（毫秒时间戳，0 = 未知）
 * @param global   是否属于全局播放队列（true = 不自动连播，手动点击发播放申请）
 */
public record QueueEntry(UUID screenId, String url, String player, long timeMs, boolean global) {

    public QueueEntry {
        url = url == null ? "" : url;
        player = player == null ? "" : player;
    }

    /** 屏幕队列条目（global=false，参与该屏自动连播）。 */
    public QueueEntry(UUID screenId, String url, String player, long timeMs) {
        this(screenId, url, player, timeMs, false);
    }
}
