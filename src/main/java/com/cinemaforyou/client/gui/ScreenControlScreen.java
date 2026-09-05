package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.video.VideoPlayer;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenState;
import com.cinemaforyou.network.ScreenActionPayload;
import com.cinemaforyou.network.UpdateScreenSettingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 屏幕控制 GUI（可滚动 + 顶部实时信息/进度条）。
 *
 * <p>顶部固定区实时显示：屏名/状态/片源、当前播放时间 / 总时长，
 * 以及带当前位置标记的总进度条。下方为滚动控制区：
 * 播放控制（等宽按钮）、片源入口、亮度/音量/大小/分辨率、声音播放设置。
 */
public class ScreenControlScreen extends ScrollableSettingsScreen {

    private static final int[] RESOLUTIONS = {360, 480, 720, 1080, 1440, 2160};
    /** 顶部实时信息区高度（虚拟内容从其下方开始）。 */
    private static final int HEADER_H = 78;

    private final UUID screenId;

    public ScreenControlScreen(UUID screenId) {
        super(Component.translatable("gui.cinemaforyou.control.title"));
        this.screenId = screenId;
    }

    private CinemaScreen currentScreen() {
        return ClientScreenManager.get().getScreen(screenId);
    }

    private ScreenState currentState() {
        return ClientScreenManager.get().getState(screenId);
    }

    private VideoPlayer currentPlayer() {
        return ClientScreenManager.get().getPlayer(screenId);
    }

    @Override
    protected void init() {
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        buildContent();
    }

    @Override
    protected void buildContent() {
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 30);
        int left = cx - w / 2;
        int y = HEADER_H;
        int rowH = 22;
        CinemaScreen screen = currentScreen();

        if (screen == null) {
            addRenderableWidget(Button.builder(Component.literal("屏幕不存在，关闭"),
                    btn -> onClose()).bounds(cx - 100, ry(y), 200, 20).build());
            finishContent(y + 26);
            return;
        }

        // ── 播放控制（两行等宽按钮） ──
        ScreenState state = currentState();
        String playPauseLabel = (state == ScreenState.PLAYING) ? "暂停" : "播放";
        int[] x4 = {left, left + 74, left + 148, left + 222};
        int bw = 72;
        addRenderableWidget(Button.builder(Component.literal(playPauseLabel),
                btn -> onPlayPause(screen)
        ).bounds(x4[0], ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("停止"),
                btn -> ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId))
        ).bounds(x4[1], ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-10s"),
                btn -> seekRelative(-10_000L)
        ).bounds(x4[2], ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+10s"),
                btn -> seekRelative(10_000L)
        ).bounds(x4[3], ry(y), bw, 20).build());
        y += rowH;

        addRenderableWidget(Button.builder(Component.literal("-30s"),
                btn -> seekRelative(-30_000L)
        ).bounds(x4[0], ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+30s"),
                btn -> seekRelative(30_000L)
        ).bounds(x4[1], ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("刷新设置"),
                btn -> sendSettings(currentScreen())
        ).bounds(x4[2], ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("重播本片"),
                btn -> {
                    CinemaScreen s = currentScreen();
                    if (s != null && !s.sourceUrl().isEmpty()) {
                        ClientNetworkHandlers.sendAction(
                                ScreenActionPayload.play(screenId, s.sourceUrl()));
                    } else {
                        hint("该屏还没有片源，请先用下方入口选择");
                    }
                }
        ).bounds(x4[3], ry(y), bw, 20).build());
        y += rowH;

        // ── 片源入口（三键等宽） ──
        int sw = (w - 4) / 3;
        addRenderableWidget(Button.builder(Component.literal("🔗 输入链接"),
                btn -> Minecraft.getInstance().gui.setScreen(new ScreenLinkInputScreen(screenId))
        ).bounds(left, ry(y), sw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("📂 本地视频"),
                btn -> Minecraft.getInstance().gui.setScreen(new VideoLibraryScreen(screenId))
        ).bounds(left + sw + 2, ry(y), sw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("🕘 播放历史"),
                btn -> Minecraft.getInstance().gui.setScreen(new HistoryScreen(screenId))
        ).bounds(left + 2 * (sw + 2), ry(y), sw, 20).build());
        y += rowH;

        addRenderableWidget(Button.builder(Component.literal("🖥 服务器媒体库（服务器 cinema/videos）"),
                btn -> Minecraft.getInstance().gui.setScreen(new ServerMediaScreen(screenId))
        ).bounds(left, ry(y), w, 20).build());
        y += rowH + 4;

        // ── 显示设置行（对称 - 值 +） ──
        y = addSettingRow("亮度", screen.brightnessPercent() + "%", cx, y,
                s -> s.withSettings(clamp(s.brightnessPercent() + 10, 0, 100),
                        s.volumePercent(), s.resolutionHeight(), s.displayScalePercent()),
                s -> s.withSettings(clamp(s.brightnessPercent() - 10, 0, 100),
                        s.volumePercent(), s.resolutionHeight(), s.displayScalePercent()));
        y = addSettingRow("音量", screen.volumePercent() + "%", cx, y,
                s -> s.withSettings(s.brightnessPercent(),
                        clamp(s.volumePercent() + 10, 0, 100), s.resolutionHeight(), s.displayScalePercent()),
                s -> s.withSettings(s.brightnessPercent(),
                        clamp(s.volumePercent() - 10, 0, 100), s.resolutionHeight(), s.displayScalePercent()));
        y = addSettingRow("大小", screen.displayScalePercent() + "%", cx, y,
                s -> s.withSettings(s.brightnessPercent(), s.volumePercent(),
                        s.resolutionHeight(), clamp(s.displayScalePercent() + 10, 25, 200)),
                s -> s.withSettings(s.brightnessPercent(), s.volumePercent(),
                        s.resolutionHeight(), clamp(s.displayScalePercent() - 10, 25, 200)));
        y = addSettingRow("分辨率", screen.resolutionHeight() + "p", cx, y,
                s -> s.withSettings(s.brightnessPercent(), s.volumePercent(),
                        nextResolution(s.resolutionHeight()), s.displayScalePercent()),
                s -> s.withSettings(s.brightnessPercent(), s.volumePercent(),
                        previousResolution(s.resolutionHeight()), s.displayScalePercent()));

        // ── 声音与播放设置 / 关闭 ──
        addRenderableWidget(Button.builder(
                Component.literal("⚙ 声音与播放设置…（范围/衰减/播完行为/队列）"),
                btn -> Minecraft.getInstance().gui.setScreen(new ScreenSoundSettingsScreen(screenId))
        ).bounds(left, ry(y), w, 20).build());
        y += rowH;

        addRenderableWidget(Button.builder(
                Component.literal("关闭"),
                btn -> onClose()
        ).bounds(cx - 50, ry(y), 100, 20).build());
        y += 26;

        finishContent(y);
    }

    // ───────────── 顶部实时信息区（每帧绘制） ─────────────

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 30);
        int left = cx - w / 2;

        CinemaScreen screen = currentScreen();
        if (screen == null) return;
        VideoPlayer player = currentPlayer();

        // 半透明底，盖住可能滚到顶部的内容
        extractor.fill(left - 8, 2, left + w + 8, HEADER_H - 2, 0xC0101014);

        String name = "屏幕: " + screen.displayName() + "  [" + screen.width() + "x" + screen.height()
                + "]  状态: " + currentState();
        extractor.centeredText(this.font, Component.literal("§e" + name), cx, 8, 0xFFFFFF);

        String src = screen.sourceUrl().isEmpty() ? "片源: （未设置，用下方入口添加）"
                : "片源: " + ClientConfig.displayNameFor(screen.sourceUrl());
        extractor.centeredText(this.font, Component.literal("§f" + UiText.fit(src, w)), cx, 24, 0xFFFFFF);

        // 时间与进度条
        long dur = player != null ? player.getDurationMs() : 0;
        long pos = player != null ? player.getPositionMs() : 0;
        if (pos < 0) pos = 0;
        int pct = dur > 0 ? (int) Math.max(0, Math.min(100, pos * 100 / dur)) : 0;
        String timeText = "▶ 当前 " + formatTime(pos) + "  /  总长 " + (dur > 0 ? formatTime(dur) : "--:--")
                + (dur > 0 ? "（" + pct + "%）" : "");
        extractor.text(this.font, Component.literal("§b" + timeText), cx - this.font.width(timeText) / 2, 40, 0xFFFFFF);

        int barY = 58;
        int barH = 4;
        extractor.fill(left, barY, left + w, barY + barH, 0x50FFFFFF); // 轨道
        if (dur > 0 && pos >= 0) {
            int filled = (int) Math.min(w, (long) w * pos / dur);
            extractor.fill(left, barY, left + filled, barY + barH, 0xFF33E06E);
            // 当前位置标记
            extractor.fill(left + filled - 1, barY - 2, left + filled + 2, barY + barH + 2, 0xFFFFFFFF);
        } else {
            extractor.fill(left, barY, left + 4, barY + barH, 0xFF33E06E);
        }
        // 进度条两端的起止时间
        extractor.text(this.font, "00:00", left, barY + 6, 0xFFB0B0B0);
        String endText = dur > 0 ? formatTime(dur) : "--:--";
        int endW = this.font.width(endText);
        extractor.text(this.font, endText, left + w - endW, barY + 6, 0xFFB0B0B0);
        // 当前时间紧贴进度条头部（进度条填充色与文字同高显示）
        String curText = formatTime(pos);
        int curW = this.font.width(curText);
        int curX = left + (dur > 0 ? (int) ((long) w * pos / dur) : 0);
        curX = Math.max(left + 2, Math.min(left + w - curW - 2, curX));
        extractor.text(this.font, curText, curX, barY - 9, 0xFFFFE53A);
    }

    private void hint(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("§7[CinemaForYou] " + msg));
        }
    }

    private void onPlayPause(CinemaScreen current) {
        ScreenState s = currentState();
        if (s == ScreenState.PLAYING) {
            ClientNetworkHandlers.sendAction(ScreenActionPayload.pause(screenId));
        } else if (s == ScreenState.PAUSED) {
            VideoPlayer p = currentPlayer();
            long dur = p != null ? p.getDurationMs() : 0;
            boolean ended = p == null || p.hasEnded()
                    || (dur > 0 && p.getPositionMs() >= dur - 1500);
            if (ended && current != null && !current.sourceUrl().isEmpty()) {
                // 播完暂停状态：重新播放而非继续
                ClientNetworkHandlers.sendAction(
                        ScreenActionPayload.play(screenId, current.sourceUrl()));
            } else {
                ClientNetworkHandlers.sendAction(ScreenActionPayload.resume(screenId));
            }
        } else if (current != null && !current.sourceUrl().isEmpty()) {
            ClientNetworkHandlers.sendAction(ScreenActionPayload.play(screenId, current.sourceUrl()));
        } else {
            hint("该屏还没有片源：点上方「🔗 输入链接」或「📂 本地视频」");
        }
    }

    private void seekRelative(long delta) {
        long base = currentPlayer() != null ? currentPlayer().getPositionMs() : 0;
        ClientNetworkHandlers.sendAction(
                ScreenActionPayload.seek(screenId, Math.max(0L, base + delta)));
    }

    /** 增减行：[-] [label] [+]，每行 22px，返回下一个 y。 */
    private int addSettingRow(String label, String value, int cx, int y,
                              java.util.function.UnaryOperator<CinemaScreen> increase,
                              java.util.function.UnaryOperator<CinemaScreen> decrease) {
        int left = cx - 105;
        addRenderableWidget(Button.builder(Component.literal(label + " -"),
                btn -> updateSettings(decrease)
        ).bounds(left, ry(y), 66, 20).build());
        Button labelButton = Button.builder(
                Component.literal("§e" + label + ": §f" + value), btn -> {}
        ).bounds(left + 68, ry(y), 74, 20).build();
        addRenderableWidget(labelButton);
        addRenderableWidget(Button.builder(Component.literal(label + " +"),
                btn -> updateSettings(increase)
        ).bounds(left + 144, ry(y), 66, 20).build());
        return y + 22;
    }

    private void sendSettings(CinemaScreen screen) {
        if (screen == null) return;
        ClientNetworkHandlers.sendScreenSettings(new UpdateScreenSettingsPayload(
                screenId,
                screen.brightnessPercent(),
                screen.volumePercent(),
                screen.resolutionHeight(),
                screen.displayScalePercent(),
                screen.audioRangeBlocks(),
                screen.audioFalloffTenths()));
    }

    private void updateSettings(java.util.function.UnaryOperator<CinemaScreen> updater) {
        CinemaScreen screen = currentScreen();
        if (screen == null) return;
        CinemaScreen updated = updater.apply(screen);
        sendSettings(updated);
        Minecraft.getInstance().execute(this::rebuildWidgets);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int previousResolution(int current) {
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (RESOLUTIONS[i] >= current) {
                return RESOLUTIONS[Math.max(0, i - 1)];
            }
        }
        return RESOLUTIONS[RESOLUTIONS.length - 1];
    }

    private static int nextResolution(int current) {
        for (int resolution : RESOLUTIONS) {
            if (resolution > current) {
                return resolution;
            }
        }
        return RESOLUTIONS[RESOLUTIONS.length - 1];
    }

    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.fill(0, 0, this.width, this.height, 0x90101014);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
