/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.cinemaforyou.client.ClientScreenManager
 *  com.cinemaforyou.client.config.ClientConfig
 *  com.cinemaforyou.client.gui.HistoryScreen
 *  com.cinemaforyou.client.gui.ScreenControlScreen$1
 *  com.cinemaforyou.client.gui.ScreenLinkInputScreen
 *  com.cinemaforyou.client.gui.ScreenQueueManagerScreen
 *  com.cinemaforyou.client.gui.ScreenSoundSettingsScreen
 *  com.cinemaforyou.client.gui.ScrollableSettingsScreen
 *  com.cinemaforyou.client.gui.ServerMediaScreen
 *  com.cinemaforyou.client.gui.UiText
 *  com.cinemaforyou.client.gui.VideoLibraryScreen
 *  com.cinemaforyou.client.network.ClientNetworkHandlers
 *  com.cinemaforyou.client.video.VideoPlayer
 *  com.cinemaforyou.data.CinemaScreen
 *  com.cinemaforyou.data.ScreenOrientation
 *  com.cinemaforyou.data.ScreenState
 *  com.cinemaforyou.network.ScreenActionPayload
 *  com.cinemaforyou.network.UpdateScreenSettingsPayload
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiGraphicsExtractor
 *  net.minecraft.client.gui.components.Button
 *  net.minecraft.client.gui.components.events.GuiEventListener
 *  net.minecraft.client.gui.screens.Screen
 *  net.minecraft.core.BlockPos
 *  net.minecraft.network.chat.Component
 */
package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.gui.HistoryScreen;
import com.cinemaforyou.client.gui.ScreenControlScreen;
import com.cinemaforyou.client.gui.ScreenLinkInputScreen;
import com.cinemaforyou.client.gui.ScreenQueueManagerScreen;
import com.cinemaforyou.client.gui.ScreenSoundSettingsScreen;
import com.cinemaforyou.client.gui.ScrollableSettingsScreen;
import com.cinemaforyou.client.gui.ServerMediaScreen;
import com.cinemaforyou.client.gui.UiText;
import com.cinemaforyou.client.gui.VideoLibraryScreen;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.video.VideoPlayer;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenOrientation;
import com.cinemaforyou.data.ScreenState;
import com.cinemaforyou.network.ScreenActionPayload;
import com.cinemaforyou.network.UpdateScreenSettingsPayload;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class ScreenControlScreen
extends ScrollableSettingsScreen {
    private static final int[] RESOLUTIONS = new int[]{360, 480, 720, 1080, 1440, 2160};
    private static final int HEADER_H = 76;
    /** 当前控制的屏幕（可由「当前屏幕选择」切换：切换后本页所有功能作用于新选中的屏幕）。 */
    private UUID screenId;
    /** 打开「当前屏幕选择」前的选择结果（lastControlScreenId）；返回后若变化说明用户点了新屏幕。 */
    private String targetBeforePick = null;

    public ScreenControlScreen(UUID screenId) {
        super(Component.translatable("gui.cinemaforyou.control.title"));
        this.screenId = screenId;
    }

    private CinemaScreen currentScreen() {
        return ClientScreenManager.get().getScreen(this.screenId);
    }

    private ScreenState currentState() {
        return ClientScreenManager.get().getState(this.screenId);
    }

    private VideoPlayer currentPlayer() {
        return ClientScreenManager.get().getPlayer(this.screenId);
    }

    /**
     * 应用「当前屏幕选择」的选择结果：与总设置里"目标屏幕"相互独立——
     * 选择结果保存在 {@link ClientConfig#lastControlScreenId}（由 TargetSearchScreen 点选时写入），
     * 这里读回并把本页控制目标切到该屏幕；仅当用户真的点选了新屏幕才切换，直接返回则保持不变。
     */
    private void applyPickedTarget() {
        if (this.targetBeforePick == null) {
            return;
        }
        String before = this.targetBeforePick;
        this.targetBeforePick = null;
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg == null) {
            return;
        }
        String picked = cfg.lastControlScreenId;
        if (picked == null || picked.isEmpty() || picked.equals(before)) {
            return; // 未点选（直接返回）：保持当前屏幕
        }
        for (CinemaScreen s : ClientScreenManager.get().allScreens().values()) {
            if (s.id().toString().equals(picked)) {
                this.screenId = s.id(); // 切换后本页所有操作（播放/设置/移动/子页面）都作用于新屏幕
                this.hint("已切换当前屏幕: " + s.displayName() + " @ " + s.center().toShortString());
                return;
            }
        }
    }

    protected void init() {
        // 从屏幕搜索选择器返回时，按选择结果切换本页当前屏幕
        this.applyPickedTarget();
        this.rebuildWidgets();
    }

    protected void rebuildWidgets() {
        this.clearWidgets();
        this.buildContent();
    }

    protected void buildContent() {
        int[] edgeOrder;
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 30);
        int left = cx - w / 2;
        // 顶部信息面板贴屏幕顶部（原 y=2..76 → 0..74），内容起始 y 随之上移 2px（78 → 76）
        int y = 76;
        int rowH = 22;
        CinemaScreen screen = this.currentScreen();
        if (screen == null) {
            this.addRenderableWidget(Button.builder(Component.literal("\u5c4f\u5e55\u4e0d\u5b58\u5728\uff0c\u8fd4\u56de"), btn -> this.onClose()).bounds(cx - 100, this.ry(y), 200, 20).build());
            this.finishContent(y + 26);
            return;
        }
        ScreenState state = this.currentState();
        String playPauseLabel = state == ScreenState.PLAYING ? "\u6682\u505c" : "\u64ad\u653e";
        int rowX = cx - 147;
        int[] x4 = new int[]{rowX, rowX + 74, rowX + 148, rowX + 222};
        int bw = 72;
        this.addRenderableWidget(Button.builder(Component.literal(playPauseLabel), btn -> this.onPlayPause(screen)).bounds(x4[0], this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u505c\u6b62"), btn -> ClientNetworkHandlers.sendAction(ScreenActionPayload.stop((UUID)this.screenId))).bounds(x4[1], this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u5237\u65b0\u8bbe\u7f6e"), btn -> this.sendSettings(this.currentScreen())).bounds(x4[2], this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u91cd\u64ad\u672c\u7247"), btn -> {
            CinemaScreen s = this.currentScreen();
            if (s != null && !s.sourceUrl().isEmpty()) {
                ClientNetworkHandlers.sendAction(ScreenActionPayload.play((UUID)this.screenId, s.sourceUrl()));
            } else {
                this.hint("\u8be5\u5c4f\u8fd8\u6ca1\u6709\u7247\u6e90\uff0c\u8bf7\u5148\u7528\u4e0b\u65b9\u5165\u53e3\u9009\u62e9");
            }
        }).bounds(x4[3], this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-30s"), btn -> this.seekRelative(-30000L)).bounds(x4[0], this.ry(y += rowH), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+30s"), btn -> this.seekRelative(30000L)).bounds(x4[1], this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-10s"), btn -> this.seekRelative(-10000L)).bounds(x4[2], this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+10s"), btn -> this.seekRelative(10000L)).bounds(x4[3], this.ry(y), bw, 20).build());
        int sw = (w - 4) / 3;
        this.addRenderableWidget(Button.builder(Component.literal("\ud83d\udd17 \u8f93\u5165\u94fe\u63a5"), btn -> this.openChild(new ScreenLinkInputScreen(this.screenId))).bounds(left, this.ry(y += rowH), sw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\ud83d\udcc2 \u672c\u5730\u89c6\u9891"), btn -> this.openChild(new VideoLibraryScreen(this.screenId))).bounds(left + sw + 2, this.ry(y), sw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\ud83d\udd58 \u64ad\u653e\u5386\u53f2"), btn -> this.openChild(new HistoryScreen(this.screenId))).bounds(left + 2 * (sw + 2), this.ry(y), sw, 20).build());
        // ── 当前屏幕选择：紧挨「服务器媒体库」上方，与下方按钮同 x/同宽/同高 ──
        // 按钮文字显示屏幕名 + 坐标，点击打开屏幕搜索选择器
        // （TargetSearchScreen 点选后写入 lastControlScreenId 并返回本页），返回后本页切换到该屏幕。
        String curScreenLabel = screen.displayName() + " @ " + screen.center().toShortString();
        this.addRenderableWidget(Button.builder(Component.literal("🎯 当前屏幕选择: " + curScreenLabel + "（点击选择）"), btn -> {
            ClientConfig cfg = CinemaForYouClient.clientConfig;
            this.targetBeforePick = cfg != null ? cfg.lastControlScreenId : null;
            this.openChild(new TargetSearchScreen());
        }).bounds(left, this.ry(y += rowH), w, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\ud83d\udda5 \u670d\u52a1\u5668\u5a92\u4f53\u5e93\uff08\u670d\u52a1\u5668 cinema/videos\uff09"), btn -> this.openChild(new ServerMediaScreen(this.screenId))).bounds(left, this.ry(y += rowH), w, 20).build());
        // 播放队列：屏幕控制入口只有两级（全部屏幕列表 → 该屏队列明细），不显示玩家层级
        this.addRenderableWidget(Button.builder(Component.literal("\u64ad\u653e\u961f\u5217"), btn -> this.openChild(ScreenQueueManagerScreen.forScreenList())).bounds(left, this.ry(y += rowH), w, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("\u2699 \u58f0\u97f3\u4e0e\u64ad\u653e\u8bbe\u7f6e\u2026\uff08\u8303\u56f4/\u8870\u51cf/\u64ad\u5b8c\u884c\u4e3a\uff09"), btn -> this.openChild(new ScreenSoundSettingsScreen(this.screenId))).bounds(left, this.ry(y += rowH), w, 20).build());
        y += rowH + 4;
        y = this.stepRow(cx, y, "\u4eae\u5ea6", screen.brightnessPercent() + "%", (d, s) -> s.withSettings(ScreenControlScreen.clamp(s.brightnessPercent() + d, 0, 100), s.volumePercent(), s.resolutionHeight(), s.displayScalePercent()));
        y = this.stepRow(cx, y, "\u97f3\u91cf", screen.volumePercent() + "%", (d, s) -> s.withSettings(s.brightnessPercent(), ScreenControlScreen.clamp(s.volumePercent() + d, 0, 100), s.resolutionHeight(), s.displayScalePercent()));
        y = this.stepRow(cx, y, "\u5927\u5c0f", screen.displayScalePercent() + "%", (d, s) -> s.withSettings(s.brightnessPercent(), s.volumePercent(), s.resolutionHeight(), ScreenControlScreen.clamp(s.displayScalePercent() + d, 25, 200)));
        y = this.stepRow(cx, y, "\u5206\u8fa8\u7387", screen.resolutionHeight() + "p", (d, s) -> s.withSettings(s.brightnessPercent(), s.volumePercent(), ScreenControlScreen.resByIndex(s.resolutionHeight(), d), s.displayScalePercent()));
        // 长条按钮「曲率类型」：上下间距统一为 22px 行距 / 2px 间隙（与其它长条按钮一致）；
        // 上方 stepRow 返回 +21，故这里 +1 补齐，下方 y += rowH 的行距不变
        int curvType = screen.curvatureType();
        this.addRenderableWidget(Button.builder(Component.literal(("\u66f2\u7387\u7c7b\u578b: " + ScreenControlScreen.curvatureTypeLabel(curvType))), btn -> {
            int next = (curvType + 1) % 5;
            this.updateSettings(s -> s.withCurvatureSettings(next, s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB()));
            if (next > 0) {
                this.hint("\u66f2\u7387\u5df2\u5f00\uff1a\u7528\u4e0b\u65b9\u5f27\u5ea6\u8c03\u5f2f\u5ea6\uff1b\u65b9\u5411\u4e0d\u5bf9\u5c31\u518d\u5207\u4e00\u6b21\u7c7b\u578b\uff08\u51f8/\u51f9\u4e92\u6362\uff09");
            } else {
                this.hint("\u5df2\u6062\u590d\u5e73\u9762\u5c4f");
            }
        }).bounds(cx - 120, this.ry(y += 1), 240, 20).build());
        y += rowH;
        y = this.stepRow(cx, y, "\u5de6\u5f27", screen.curvDegL() + "\u00b0", (d, s) -> s.withCurvatureSettings(s.curvatureType(), ScreenControlScreen.clamp(s.curvDegL() + d, 0, 90), s.curvDegR(), s.curvDegT(), s.curvDegB()));
        y = this.stepRow(cx, y, "\u53f3\u5f27", screen.curvDegR() + "\u00b0", (d, s) -> s.withCurvatureSettings(s.curvatureType(), s.curvDegL(), ScreenControlScreen.clamp(s.curvDegR() + d, 0, 90), s.curvDegT(), s.curvDegB()));
        if (curvType == 3 || curvType == 4) {
            y = this.stepRow(cx, y, "\u4e0a\u5f27", screen.curvDegT() + "\u00b0", (d, s) -> s.withCurvatureSettings(s.curvatureType(), s.curvDegL(), s.curvDegR(), ScreenControlScreen.clamp(s.curvDegT() + d, 0, 90), s.curvDegB()));
            y = this.stepRow(cx, y, "\u4e0b\u5f27", screen.curvDegB() + "\u00b0", (d, s) -> s.withCurvatureSettings(s.curvatureType(), s.curvDegL(), s.curvDegR(), s.curvDegT(), ScreenControlScreen.clamp(s.curvDegB() + d, 0, 90)));
        }
        y = this.stepRow(cx, y, "\u5de6\u53f3\u503e\u659c", screen.tiltDegH() + "\u00b0", (d, s) -> s.withTiltSettings(ScreenControlScreen.clamp(s.tiltDegH() + d, -180, 180), s.tiltDegV()));
        y = this.stepRow(cx, y, "\u4e0a\u4e0b\u4fef\u4ef0", screen.tiltDegV() + "\u00b0", (d, s) -> s.withTiltSettings(s.tiltDegH(), ScreenControlScreen.clamp(s.tiltDegV() + d, -180, 180)));
        // 分组按钮「移动屏幕」（240 宽、居中）：上下间距统一为 22px 行距 / 2px 间隙（与其它长条按钮一致）；
        // 上方 actionRow 返回 +21，故把原来的 +2 改为 +1 补齐，下方 y += 22 的行距不变
        this.addRenderableWidget(Button.builder(Component.literal("\u79fb\u52a8\u5c4f\u5e55"), btn -> {}).bounds(cx - 120, this.ry(y += 1), 240, 20).build());
        y += 22;
        int[] hDir = ScreenControlScreen.horizontalDelta(screen.orientation());
        int[] vDir = ScreenControlScreen.verticalDelta(screen.orientation());
        int[] nDir = ScreenControlScreen.normalDelta(screen.orientation());
        y = this.actionRow(cx, y, "\u00a7e\u6a2a\u79fb\u5c4f\u5e55 \u00a7f" + ScreenControlScreen.signed(this.moveValue(screen, hDir)), () -> this.moveScreenBy(hDir, -10), () -> this.moveScreenBy(hDir, -1), () -> this.moveScreenBy(hDir, 1), () -> this.moveScreenBy(hDir, 10));
        y = this.actionRow(cx, y, "\u00a7e\u7eb5\u79fb\u5c4f\u5e55 \u00a7f" + ScreenControlScreen.signed(this.moveValue(screen, vDir)), () -> this.moveScreenBy(vDir, -10), () -> this.moveScreenBy(vDir, -1), () -> this.moveScreenBy(vDir, 1), () -> this.moveScreenBy(vDir, 10));
        y = this.actionRow(cx, y, "\u00a7e\u524d\u540e\u79fb\u5c4f \u00a7f" + ScreenControlScreen.signed(this.moveValue(screen, nDir)), () -> this.moveScreenBy(nDir, -10), () -> this.moveScreenBy(nDir, -1), () -> this.moveScreenBy(nDir, 1), () -> this.moveScreenBy(nDir, 10));
        // 分组按钮「屏边拉缩」（240 宽、居中）：上下间距统一为 22px 行距 / 2px 间隙（与其它长条按钮一致）；
        // 上方 actionRow 返回 +21，故把原来的 +2 改为 +1 补齐，下方 y += 22 的行距不变
        this.addRenderableWidget(Button.builder(Component.literal("\u5c4f\u8fb9\u62c9\u7f29"), btn -> {}).bounds(cx - 120, this.ry(y += 1), 240, 20).build());
        y += 22;
        int[] nArray = edgeOrder = new int[]{2, 3, 0, 1};
        int n = nArray.length;
        for (int i = 0; i < n; ++i) {
            int edge;
            int e = edge = nArray[i];
            y = this.actionRow(cx, y, "\u00a7e" + ScreenControlScreen.edgeName(e) + " \u00a7f" + ScreenControlScreen.signed(this.edgeValue(screen, e)), () -> this.resizeScreenEdge(e, -10), () -> this.resizeScreenEdge(e, -1), () -> this.resizeScreenEdge(e, 1), () -> this.resizeScreenEdge(e, 10));
        }
        // 底部按钮：改名「关闭」、宽 240，与上一行操作按钮同列（cx-120 起）并紧贴其上（去掉原 y += rowH 的空档）
        this.addRenderableWidget(Button.builder(Component.literal("\u5173\u95ed"), btn -> this.onClose()).bounds(cx - 120, this.ry(y), 240, 20).build());
        this.finishContent(y += 26);
    }

    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        long pos;
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 30);
        int left = cx - w / 2;
        CinemaScreen screen = this.currentScreen();
        if (screen == null) {
            return;
        }
        VideoPlayer player = this.currentPlayer();
        extractor.fill(left - 8, 0, left + w + 8, 74, -1072689132);
        String name = "\u5c4f\u5e55: " + screen.displayName() + "  [" + screen.width() + "x" + screen.height() + "]  \u72b6\u6001: " + String.valueOf(this.currentState());
        extractor.centeredText(this.font, Component.literal(("\u00a7e" + name)), cx, 6, 0xFFFFFF);
        String src = screen.sourceUrl().isEmpty() ? "\u7247\u6e90: \uff08\u672a\u8bbe\u7f6e\uff0c\u7528\u4e0b\u65b9\u5165\u53e3\u6dfb\u52a0\uff09" : "\u7247\u6e90: " + ClientConfig.displayNameFor(screen.sourceUrl());
        extractor.centeredText(this.font, Component.literal(("\u00a7f" + UiText.fit(src, (int)w))), cx, 22, 0xFFFFFF);
        long dur = player != null ? player.getDurationMs() : 0L;
        long l = pos = player != null ? player.getPositionMs() : 0L;
        if (pos < 0L) {
            pos = 0L;
        }
        int pct = dur > 0L ? (int)Math.max(0L, Math.min(100L, pos * 100L / dur)) : 0;
        String timeText = "\u25b6 \u5f53\u524d " + ScreenControlScreen.formatTime(pos) + "  /  \u603b\u957f " + (dur > 0L ? ScreenControlScreen.formatTime(dur) : "--:--") + (dur > 0L ? "\uff08" + pct + "%\uff09" : "");
        extractor.text(this.font, Component.literal(("\u00a7b" + timeText)), cx - this.font.width(timeText) / 2, 38, 0xFFFFFF);
        int barY = 56;
        int barH = 4;
        extractor.fill(left, barY, left + w, barY + barH, 0x50FFFFFF);
        if (dur > 0L && pos >= 0L) {
            int filled = (int)Math.min((long)w, (long)w * pos / dur);
            extractor.fill(left, barY, left + filled, barY + barH, -13377426);
            extractor.fill(left + filled - 1, barY - 2, left + filled + 2, barY + barH + 2, -1);
        } else {
            extractor.fill(left, barY, left + 4, barY + barH, -13377426);
        }
        extractor.text(this.font, "00:00", left, barY + 6, -5197648);
        String endText = dur > 0L ? ScreenControlScreen.formatTime(dur) : "--:--";
        int endW = this.font.width(endText);
        extractor.text(this.font, endText, left + w - endW, barY + 6, -5197648);
        String curText = ScreenControlScreen.formatTime(pos);
        int curW = this.font.width(curText);
        int curX = left + (dur > 0L ? (int)((long)w * pos / dur) : 0);
        curX = Math.max(left + 2, Math.min(left + w - curW - 2, curX));
        extractor.text(this.font, curText, curX, barY - 9, -6854);
    }

    private void hint(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(("\u00a77[CinemaForYou] " + msg)));
        }
    }

    private void onPlayPause(CinemaScreen current) {
        ScreenState s = this.currentState();
        if (s == ScreenState.PLAYING) {
            ClientNetworkHandlers.sendAction(ScreenActionPayload.pause((UUID)this.screenId));
        } else if (s == ScreenState.PAUSED) {
            boolean ended;
            VideoPlayer p = this.currentPlayer();
            long dur = p != null ? p.getDurationMs() : 0L;
            boolean bl = ended = p == null || p.hasEnded() || dur > 0L && p.getPositionMs() >= dur - 1500L;
            if (ended && current != null && !current.sourceUrl().isEmpty()) {
                ClientNetworkHandlers.sendAction(ScreenActionPayload.play((UUID)this.screenId, current.sourceUrl()));
            } else {
                ClientNetworkHandlers.sendAction(ScreenActionPayload.resume((UUID)this.screenId));
            }
        } else if (current != null && !current.sourceUrl().isEmpty()) {
            ClientNetworkHandlers.sendAction(ScreenActionPayload.play((UUID)this.screenId, current.sourceUrl()));
        } else {
            this.hint("\u8be5\u5c4f\u8fd8\u6ca1\u6709\u7247\u6e90\uff1a\u70b9\u4e0a\u65b9\u300c\ud83d\udd17 \u8f93\u5165\u94fe\u63a5\u300d\u6216\u300c\ud83d\udcc2 \u672c\u5730\u89c6\u9891\u300d");
        }
    }

    private void seekRelative(long delta) {
        long base = this.currentPlayer() != null ? this.currentPlayer().getPositionMs() : 0L;
        ClientNetworkHandlers.sendAction(ScreenActionPayload.seek((UUID)this.screenId, (long)Math.max(0L, base + delta)));
    }

    private int addSettingRow(String label, String value, int cx, int y, UnaryOperator<CinemaScreen> increase, UnaryOperator<CinemaScreen> decrease) {
        int left = cx - 105;
        this.addRenderableWidget(Button.builder(Component.literal((label + " -")), btn -> this.updateSettings(decrease)).bounds(left, this.ry(y), 66, 20).build());
        Button labelButton = Button.builder(Component.literal(("\u00a7e" + label + ": \u00a7f" + value)), btn -> {}).bounds(left + 68, this.ry(y), 74, 20).build();
        this.addRenderableWidget(labelButton);
        this.addRenderableWidget(Button.builder(Component.literal((label + " +")), btn -> this.updateSettings(increase)).bounds(left + 144, this.ry(y), 66, 20).build());
        return y + 22;
    }

    private int stepRow(int cx, int y, String label, String valueText, BiFunction<Integer, CinemaScreen, CinemaScreen> applier) {
        int big = 36;
        int small = 30;
        int mid = 96;
        int gap = 3;
        int total = big + gap + small + gap + mid + gap + small + gap + big;
        int x0 = cx - total / 2;
        int x1 = x0 + big + gap;
        int xMid = x1 + small + gap;
        int x3 = xMid + mid + gap;
        int x4 = x3 + small + gap;
        this.addRenderableWidget(Button.builder(Component.literal(("\u00a7e" + label + " \u00a7f" + valueText)), btn -> {}).bounds(xMid, this.ry(y), mid, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-10"), b -> this.updateSettings(s -> (CinemaScreen)applier.apply(-10, (CinemaScreen)s))).bounds(x0, this.ry(y), big, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-1"), b -> this.updateSettings(s -> (CinemaScreen)applier.apply(-1, (CinemaScreen)s))).bounds(x1, this.ry(y), small, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+1"), b -> this.updateSettings(s -> (CinemaScreen)applier.apply(1, (CinemaScreen)s))).bounds(x3, this.ry(y), small, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+10"), b -> this.updateSettings(s -> (CinemaScreen)applier.apply(10, (CinemaScreen)s))).bounds(x4, this.ry(y), big, 20).build());
        return y + 21;
    }

    private int actionRow(int cx, int y, String centerText, Runnable dec10, Runnable dec1, Runnable inc1, Runnable inc10) {
        int big = 36;
        int small = 30;
        int mid = 96;
        int gap = 3;
        int total = big + gap + small + gap + mid + gap + small + gap + big;
        int x0 = cx - total / 2;
        int x1 = x0 + big + gap;
        int xMid = x1 + small + gap;
        int x3 = xMid + mid + gap;
        int x4 = x3 + small + gap;
        this.addRenderableWidget(Button.builder(Component.literal(centerText), btn -> {}).bounds(xMid, this.ry(y), mid, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-10"), b -> dec10.run()).bounds(x0, this.ry(y), big, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("-1"), b -> dec1.run()).bounds(x1, this.ry(y), small, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+1"), b -> inc1.run()).bounds(x3, this.ry(y), small, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("+10"), b -> inc10.run()).bounds(x4, this.ry(y), big, 20).build());
        return y + 21;
    }

    private static String signed(int v) {
        return (v >= 0 ? "+" : "") + v;
    }

    private int moveValue(CinemaScreen s, int[] vec) {
        BlockPos[] b = ClientScreenManager.get().baselineOf(this.screenId);
        if (b == null) {
            return 0;
        }
        int dx = s.corner1().getX() + s.corner2().getX() - (b[0].getX() + b[1].getX());
        int dy = s.corner1().getY() + s.corner2().getY() - (b[0].getY() + b[1].getY());
        int dz = s.corner1().getZ() + s.corner2().getZ() - (b[0].getZ() + b[1].getZ());
        return (dx * vec[0] + dy * vec[1] + dz * vec[2]) / 2;
    }

    private int edgeValue(CinemaScreen s, int edge) {
        BlockPos[] b = ClientScreenManager.get().baselineOf(this.screenId);
        if (b == null) {
            return 0;
        }
        int[] axes = ScreenControlScreen.planeAxes(s.orientation());
        int axis = edge == 0 || edge == 1 ? axes[0] : axes[1];
        int cMin = Math.min(ScreenControlScreen.axisValue(s.corner1(), axis), ScreenControlScreen.axisValue(s.corner2(), axis));
        int cMax = Math.max(ScreenControlScreen.axisValue(s.corner1(), axis), ScreenControlScreen.axisValue(s.corner2(), axis));
        int bMin = Math.min(ScreenControlScreen.axisValue(b[0], axis), ScreenControlScreen.axisValue(b[1], axis));
        int bMax = Math.max(ScreenControlScreen.axisValue(b[0], axis), ScreenControlScreen.axisValue(b[1], axis));
        boolean onMaxSide = edge == 1 || edge == 2;
        int curE = onMaxSide ? cMax : cMin;
        int baseE = onMaxSide ? bMax : bMin;
        int outSign = edge == 0 || edge == 3 ? -1 : 1;
        return (curE - baseE) * outSign;
    }

    private static int resByIndex(int current, int step) {
        int idx = 0;
        for (int i = 0; i < RESOLUTIONS.length; ++i) {
            if (RESOLUTIONS[i] < current) continue;
            idx = i;
            break;
        }
        idx = ScreenControlScreen.clamp(idx + step, 0, RESOLUTIONS.length - 1);
        return RESOLUTIONS[idx];
    }

    private void sendSettings(CinemaScreen screen) {
        if (screen == null) {
            return;
        }
        ClientNetworkHandlers.sendScreenSettings((UpdateScreenSettingsPayload)new UpdateScreenSettingsPayload(this.screenId, screen.brightnessPercent(), screen.volumePercent(), screen.resolutionHeight(), screen.displayScalePercent(), screen.audioRangeBlocks(), screen.audioFalloffTenths(), screen.curvatureType(), screen.curvDegL(), screen.curvDegR(), screen.curvDegT(), screen.curvDegB(), screen.tiltDegH(), screen.tiltDegV()));
    }

    private void updateSettings(UnaryOperator<CinemaScreen> updater) {
        CinemaScreen screen = this.currentScreen();
        if (screen == null) {
            return;
        }
        CinemaScreen updated = (CinemaScreen)updater.apply(screen);
        ClientScreenManager.get().localApplyScreen(updated);
        this.sendSettings(updated);
        Minecraft.getInstance().execute(this::rebuildWidgets);
    }

    private int moveButtons(int cx, int y, String negLabel, String posLabel, int[] vec) {
        int bw = 48;
        int gap = 4;
        int x0 = cx - 105;
        this.addRenderableWidget(Button.builder(Component.literal((negLabel + "-10")), b -> this.moveScreenBy(vec, -10)).bounds(x0, this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal((negLabel + "-1")), b -> this.moveScreenBy(vec, -1)).bounds(x0 + bw + gap, this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal((posLabel + "+1")), b -> this.moveScreenBy(vec, 1)).bounds(x0 + 2 * (bw + gap), this.ry(y), bw, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal((posLabel + "+10")), b -> this.moveScreenBy(vec, 10)).bounds(x0 + 3 * (bw + gap), this.ry(y), bw, 20).build());
        return y + 20;
    }

    private void moveScreenBy(int[] vec, int steps) {
        CinemaScreen s = this.currentScreen();
        if (s == null) {
            return;
        }
        int dx = vec[0] * steps;
        int dy = vec[1] * steps;
        int dz = vec[2] * steps;
        CinemaScreen moved = new CinemaScreen(s.id(), s.corner1().offset(dx, dy, dz), s.corner2().offset(dx, dy, dz), s.orientation(), s.sourceUrl(), s.ownerId(), s.createdAt(), s.customId(), s.brightnessPercent(), s.volumePercent(), s.resolutionHeight(), s.displayScalePercent(), s.audioRangeBlocks(), s.audioFalloffTenths(), s.curvatureType(), s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB(), s.tiltDegH(), s.tiltDegV());
        ClientScreenManager.get().localApplyScreen(moved);
        ClientNetworkHandlers.sendMove((UUID)this.screenId, (int)dx, (int)dy, (int)dz);
        Minecraft.getInstance().execute(this::rebuildWidgets);
    }

    private static int[] horizontalDelta(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{1, 0, 0};
            case AXIS_X -> new int[]{0, 0, 1};
            case AXIS_Y -> new int[]{1, 0, 0};
        };
    }

    private static int[] verticalDelta(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{0, 1, 0};
            case AXIS_X -> new int[]{0, 1, 0};
            case AXIS_Y -> new int[]{0, 0, 1};
        };
    }

    private static int[] normalDelta(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{0, 0, 1};
            case AXIS_X -> new int[]{1, 0, 0};
            case AXIS_Y -> new int[]{0, 1, 0};
        };
    }

    private static String edgeName(int edge) {
        return switch (edge) {
            case 0 -> "\u5de6";
            case 1 -> "\u53f3";
            case 2 -> "\u4e0a";
            default -> "\u4e0b";
        };
    }

    private static int[] planeAxes(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{0, 1};
            case AXIS_X -> new int[]{2, 1};
            case AXIS_Y -> new int[]{0, 2};
        };
    }

    private void resizeScreenEdge(int edge, int step) {
        CinemaScreen s = this.currentScreen();
        if (s == null) {
            return;
        }
        int[] axes = ScreenControlScreen.planeAxes(s.orientation());
        int axis = edge == 0 || edge == 1 ? axes[0] : axes[1];
        int a1 = ScreenControlScreen.axisValue(s.corner1(), axis);
        int a2 = ScreenControlScreen.axisValue(s.corner2(), axis);
        int minV = Math.min(a1, a2);
        int maxV = Math.max(a1, a2);
        boolean onMaxSide = edge == 1 || edge == 2;
        int edgeVal = onMaxSide ? maxV : minV;
        int outSign = edge == 0 || edge == 3 ? -1 : 1;
        int delta = outSign * step;
        int[] dd1 = ScreenControlScreen.axisDelta(axis, 0);
        int[] dd2 = ScreenControlScreen.axisDelta(axis, 0);
        if (a1 == edgeVal) {
            dd1 = ScreenControlScreen.axisDelta(axis, delta);
        }
        if (a2 == edgeVal) {
            dd2 = ScreenControlScreen.axisDelta(axis, delta);
        }
        CinemaScreen moved = new CinemaScreen(s.id(), s.corner1().offset(dd1[0], dd1[1], dd1[2]), s.corner2().offset(dd2[0], dd2[1], dd2[2]), s.orientation(), s.sourceUrl(), s.ownerId(), s.createdAt(), s.customId(), s.brightnessPercent(), s.volumePercent(), s.resolutionHeight(), s.displayScalePercent(), s.audioRangeBlocks(), s.audioFalloffTenths(), s.curvatureType(), s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB(), s.tiltDegH(), s.tiltDegV());
        ClientScreenManager.get().localApplyScreen(moved);
        ClientNetworkHandlers.sendResize((UUID)this.screenId, (int)dd1[0], (int)dd1[1], (int)dd1[2], (int)dd2[0], (int)dd2[1], (int)dd2[2]);
        Minecraft.getInstance().execute(this::rebuildWidgets);
    }

    private static int axisValue(BlockPos p, int axis) {
        return switch (axis) {
            case 0 -> p.getX();
            case 1 -> p.getY();
            default -> p.getZ();
        };
    }

    private static int[] axisDelta(int axis, int delta) {
        int[] d = new int[3];
        d[axis] = delta;
        return d;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String curvatureTypeLabel(int type) {
        return switch (type) {
            case 1 -> "\u6c34\u5e73\u51f8\u5f27";
            case 2 -> "\u6c34\u5e73\u51f9\u5f27";
            case 3 -> "\u53cc\u5411\u51f8\u5f27(\u7403\u9762)";
            case 4 -> "\u53cc\u5411\u51f9\u5f27(\u7403\u9762)";
            default -> "\u5e73\u9762";
        };
    }

    private static int previousResolution(int current) {
        for (int i = 0; i < RESOLUTIONS.length; ++i) {
            if (RESOLUTIONS[i] < current) continue;
            return RESOLUTIONS[Math.max(0, i - 1)];
        }
        return RESOLUTIONS[RESOLUTIONS.length - 1];
    }

    private static int nextResolution(int current) {
        for (int resolution : RESOLUTIONS) {
            if (resolution <= current) continue;
            return resolution;
        }
        return RESOLUTIONS[RESOLUTIONS.length - 1];
    }

    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = totalSeconds % 3600L / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }

    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.fill(0, 0, this.width, this.height, -1877995500);
    }

    public boolean isPauseScreen() {
        return false;
    }
}
