package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.network.ScreenActionPayload;
import com.cinemaforyou.network.UpdateScreenSettingsPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * 屏幕"声音与播放"设置（可滚动）：传播范围/距离衰减（服务端每屏字段，0=跟随全局默认）、
 * 播完行为（本机偏好）、播放队列与历史入口。从屏幕控制页的"⚙ 声音与播放设置…"打开。
 */
public class ScreenSoundSettingsScreen extends ScrollableSettingsScreen {

    /** 可选的声音范围（格）：0 = 跟随全局默认。 */
    private static final int[] RANGES = {0, 16, 32, 64, 128, 256, 512};
    /** 可选的距离衰减（指数×10）：0 = 跟随全局默认。 */
    private static final int[] FALLOFFS = {0, 5, 10, 15, 20, 30};

    private final UUID screenId;

    public ScreenSoundSettingsScreen(UUID screenId) {
        super(Component.literal("声音与播放设置"));
        this.screenId = screenId;
    }

    private CinemaScreen currentScreen() {
        return ClientScreenManager.get().getScreen(screenId);
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
        int w = Math.min(300, this.width - 40);
        int y = 8;
        CinemaScreen screen = currentScreen();
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (screen == null || cfg == null) {
            addRenderableWidget(Button.builder(Component.literal("屏幕不存在，关闭"),
                    btn -> onClose()).bounds(cx - 100, ry(y), 200, 20).build());
            finishContent(y + 26);
            return;
        }

        addLabel("§e⚙ 声音与播放设置  §7屏幕: " + screen.displayName()
                + "  @ " + screen.center().toShortString(), cx, ry(y), w);
        y += 22;

        // ── 声音传播范围（每屏覆盖，0=全局默认） ──
        int range = screen.audioRangeBlocks();
        addRenderableWidget(cycleButton(cx, y, w,
                "声音距离: " + rangeLabel(range, cfg), () -> {
                    int idx = indexOf(RANGES, range);
                    int next = RANGES[(idx + 1) % RANGES.length];
                    sendAudioSettings(next, screen.audioFalloffTenths());
                }));
        y += 22;

        // ── 距离衰减（每屏覆盖，0=全局默认） ──
        int falloff = screen.audioFalloffTenths();
        addRenderableWidget(cycleButton(cx, y, w,
                "距离衰减: " + falloffLabel(falloff), () -> {
                    int idx = indexOf(FALLOFFS, falloff);
                    int next = FALLOFFS[(idx + 1) % FALLOFFS.length];
                    sendAudioSettings(screen.audioRangeBlocks(), next);
                }));
        y += 22;

        // ── 播完行为（本机偏好） ──
        int mode = cfg.playModeFor(screenId.toString());
        addRenderableWidget(cycleButton(cx, y, w,
                "播完: " + modeLabel(mode), () -> {
                    int next = (cfg.playModeFor(screenId.toString()) + 1) % 4;
                    cfg.setPlayMode(screenId.toString(), next);
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                            "§7[CinemaForYou] 此屏播完行为: §a" + modeLabel(next)
                                    + (next == 2 ? " §7（按下方队列顺序）" : "")));
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }));
        y += 22;

        // ── 队列信息 + 清空 / 管理 ──
        int queueSize = cfg.queueFor(screenId.toString()).size();
        int half = w / 2 - 3;
        addRenderableWidget(Button.builder(
                Component.literal("🗑 清空队列（" + queueSize + "）"),
                btn -> {
                    cfg.clearQueue(screenId.toString());
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                            "§7[CinemaForYou] 已清空此屏播放队列"));
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }
        ).bounds(cx - w / 2, ry(y), half, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal("📋 查看/调整队列…"),
                btn -> Minecraft.getInstance().gui.setScreen(
                        new ScreenQueueManagerScreen(screenId))
        ).bounds(cx + 3, ry(y), half, 20).build());
        y += 24;

        // ── 本地视频 / 历史 ──
        addRenderableWidget(Button.builder(
                Component.literal("📂 浏览本地视频（播放或加入队列）"),
                btn -> Minecraft.getInstance().gui.setScreen(new VideoLibraryScreen(screenId))
        ).bounds(cx - w / 2, ry(y), w, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(
                Component.literal("🕘 播放历史记录"),
                btn -> Minecraft.getInstance().gui.setScreen(new HistoryScreen(screenId))
        ).bounds(cx - w / 2, ry(y), w, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(
                Component.literal("🖥 服务器媒体库（服务器 cinema/videos）"),
                btn -> Minecraft.getInstance().gui.setScreen(new ServerMediaScreen(screenId))
        ).bounds(cx - w / 2, ry(y), w, 20).build());
        y += 24;

        int hintW = Math.min(320, this.width - 30);
        for (String line : UiText.wrap("§7提示：播完=自动播放下一个时按队列顺序循环；循环本片=重复当前视频；"
                + "播完暂停=保留末帧。", hintW)) {
            addLabel(line, cx, ry(y), hintW);
            y += 11;
        }
        y += 2;

        addRenderableWidget(Button.builder(
                Component.literal("关闭"),
                btn -> onClose()
        ).bounds(cx - 50, ry(y), 100, 20).build());
        y += 26;

        finishContent(y);
    }

    private Button cycleButton(int cx, int y, int w, String label, Runnable onClick) {
        return Button.builder(Component.literal(label),
                btn -> {
                    onClick.run();
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }
        ).bounds(cx - w / 2, ry(y), w, 20).build();
    }

    /** 发送范围/衰减覆盖到服务端（连同该屏现有显示设置一起）。 */
    private void sendAudioSettings(int audioRange, int audioFalloff) {
        CinemaScreen s = currentScreen();
        if (s == null) return;
        ClientNetworkHandlers.sendScreenSettings(new UpdateScreenSettingsPayload(
                screenId,
                s.brightnessPercent(),
                s.volumePercent(),
                s.resolutionHeight(),
                s.displayScalePercent(),
                audioRange,
                audioFalloff));
    }

    /** 播放指定源到目标屏幕（列表/历史屏共用入口）。 */
    public static void playOn(UUID screenId, String url) {
        if (screenId == null || url == null || url.isEmpty()) return;
        ClientNetworkHandlers.sendAction(ScreenActionPayload.play(screenId, url));
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null) {
            cfg.addHistory(url);
        }
    }

    private static String rangeLabel(int v, ClientConfig cfg) {
        if (v == 0) {
            return "默认（全局 " + (cfg != null ? cfg.audioMaxDistance : 128) + " 格）";
        }
        return v + " 格";
    }

    private static String falloffLabel(int v) {
        return switch (v) {
            case 0 -> "默认（跟随全局）";
            case 5 -> "0.5x 慢衰减";
            case 10 -> "1.0x 线性";
            case 15 -> "1.5x 较快";
            case 20 -> "2.0x 快";
            case 30 -> "3.0x 极快";
            default -> (v / 10.0) + "x";
        };
    }

    private static String modeLabel(int mode) {
        return switch (mode) {
            case 1 -> "循环本片";
            case 2 -> "自动播放下一个";
            case 3 -> "播完暂停（保留末帧）";
            default -> "停止";
        };
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == v) return i;
        }
        return 0;
    }

    /** 以屏幕中心线居中的文字（黄色高亮）。cx = 中心线坐标。 */
    private void addLabel(String text, int cx, int y, int w) {
        addRenderableWidget(new GuiTextLabel(cx, y, w, 12, text,
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
