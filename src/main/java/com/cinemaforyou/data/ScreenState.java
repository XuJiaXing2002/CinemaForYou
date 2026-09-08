package com.cinemaforyou.data;

/**
 * 屏幕播放状态枚举。
 *
 * <p>状态流转：IDLE → PLAYING ⇄ PAUSED → STOPPED → IDLE
 */
public enum ScreenState {
    /** 空闲：无视频源或已停止。 */
    IDLE,
    /** 播放中：视频正在解码与渲染。 */
    PLAYING,
    /** 暂停：暂停在当前帧，可恢复。 */
    PAUSED,
    /** 已停止：源已清空，回到 IDLE。 */
    STOPPED;

    public boolean isActive() {
        return this == PLAYING || this == PAUSED;
    }
}
