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

import java.util.ArrayList;
import java.util.List;
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
        // 内容起始 y=2：标题贴屏幕顶部，去掉原来的顶部留白（行距保持不变）
        int y = 2;
        CinemaScreen screen = currentScreen();
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (screen == null || cfg == null) {
            addRenderableWidget(Button.builder(Component.literal("屏幕不存在，返回"),
                    btn -> onClose()).bounds(cx - 100, ry(y), 200, 20).build());
            finishContent(y + 26);
            return;
        }

        addLabel("§e⚙ 声音与播放设置  §7屏幕: " + screen.displayName()
                + "  @ " + screen.center().toShortString(), cx, ry(y), w);
        y += 22;

        // 权限：范围/衰减是屏幕字段（会改动对方屏幕），非 owner 且非 OP≥2 置灰；
        // 音频延迟与播完行为是本机偏好，不受影响
        boolean canManage = ScreenControlScreen.canManageScreen(screen);
        if (!canManage) {
            addGrayLabel("非本人屏幕不可操作：声音距离/衰减已置灰（播放与队列查看不受限）",
                    cx, ry(y), w);
            y += 14;
        }

        // ── 声音传播范围（每屏覆盖，0=全局默认） ──
        int range = screen.audioRangeBlocks();
        addRenderableWidget(cycleButton(cx, y, 240,
                "声音距离: " + rangeLabel(range, cfg), canManage, () -> {
                    int idx = indexOf(RANGES, range);
                    int next = RANGES[(idx + 1) % RANGES.length];
                    sendAudioSettings(next, screen.audioFalloffTenths());
                }));
        y += 22;

        // ── 距离衰减（每屏覆盖，0=全局默认） ──
        int falloff = screen.audioFalloffTenths();
        addRenderableWidget(cycleButton(cx, y, 240,
                "距离衰减: " + falloffLabel(falloff), canManage, () -> {
                    int idx = indexOf(FALLOFFS, falloff);
                    int next = FALLOFFS[(idx + 1) % FALLOFFS.length];
                    sendAudioSettings(screen.audioRangeBlocks(), next);
                }));
        y += 22;

        // ── 音频延迟补偿（本屏覆盖；与全局相同时跟随全局） ──
        int effLat = cfg.effectiveAudioLatencyMs(screenId.toString());
        String latLabel = effLat == 0
                ? "音频延迟: §a0ms§7（默认不补偿，点击修改）"
                : "音频延迟: §a" + effLat + "ms§7（点击修改）";
        addRenderableWidget(Button.builder(
                Component.literal(latLabel),
                btn -> openLatencyEditor(screenId, cfg))
                .bounds(cx - 120, ry(y), 240, 20).build());
        y += 22;

        // ── 播完行为（0=跟随全局默认） ──
        Integer rawMode = cfg.rawPlayMode(screenId.toString());
        int base = rawMode == null ? 0 : rawMode;
        String display = rawMode == null
                ? "跟随全局（当前全局: " + modeLabel(cfg.defaultPlayMode) + "）"
                : modeLabel(rawMode);
        addRenderableWidget(cycleButton(cx, y, 240,
                "播完: " + display, true, () -> {
                    Integer cur = cfg.rawPlayMode(screenId.toString());
                    int curBase = cur == null ? 0 : cur;
                    int next = (curBase + 1) % 4;
                    cfg.setPlayMode(screenId.toString(), next);
                    String shown = next == 0
                            ? "跟随全局（当前全局: " + modeLabel(cfg.defaultPlayMode) + "）"
                            : modeLabel(next);
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                            "§7[CinemaForYou] 此屏播完行为: §a" + shown
                                    + (next == 2 ? " §7（播放下一个按播放队列顺序）" : "")
                                    + (next == 0 ? " §7（在总设置里改全局默认）" : "")));
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }));
        y += 22;

        int hintW = Math.min(320, this.width - 30);
        for (String line : UiText.wrap("§7提示：范围/衰减是本屏对声音的覆盖（0=跟随全局默认）；"
                + "播放队列请在控制页的「播放队列…」或总设置里管理。", hintW)) {
            addLabel(line, cx, ry(y), hintW);
            y += 11;
        }
        y += 2;

        addRenderableWidget(Button.builder(
                Component.literal("← 返回上一级"),
                btn -> onClose()
        ).bounds(cx - 50, ry(y), 100, 20).build());
        y += 26;

        finishContent(y);
    }

    /** 打开本屏音频延迟输入框（0-500ms；保存后返回本页自动刷新显示）。 */
    private void openLatencyEditor(UUID id, ClientConfig cfg) {
        String init = String.valueOf(cfg.effectiveAudioLatencyMs(id.toString()));
        openChild(new InputValueScreen(
                "音频延迟补偿（0-500ms）\n声音比画面慢→调大；画面比声音慢→调小；0=不补偿",
                init, 6,
                v -> {
                    try {
                        int ms = Integer.parseInt(v.trim());
                        cfg.setScreenAudioLatencyMs(id.toString(), ms);
                    } catch (Exception ignored) {
                        // 输入非法：保持原值
                    }
                }));
    }

    /** 循环切换按钮；enabled=false 时置灰不可点（非本人屏幕的设置项）。 */
    private Button cycleButton(int cx, int y, int w, String label, boolean enabled, Runnable onClick) {
        Button btn = Button.builder(Component.literal(label),
                b -> {
                    onClick.run();
                    Minecraft.getInstance().execute(this::rebuildWidgets);
                }
        ).bounds(cx - w / 2, ry(y), w, 20).build();
        btn.active = enabled;
        return btn;
    }

    /** 发送范围/衰减覆盖到服务端（连同该屏现有显示设置一起）。 */
    private void sendAudioSettings(int audioRange, int audioFalloff) {
        CinemaScreen s = currentScreen();
        if (s == null) return;
        CinemaScreen updated = s.withAudioSettings(audioRange, audioFalloff);
        ClientScreenManager.get().localApplyScreen(updated); // 乐观本地更新，UI 立即刷新
        ClientNetworkHandlers.sendScreenSettings(new UpdateScreenSettingsPayload(
                screenId,
                updated.brightnessPercent(),
                updated.volumePercent(),
                updated.resolutionHeight(),
                updated.displayScalePercent(),
                updated.audioRangeBlocks(),
                updated.audioFalloffTenths(),
                updated.curvatureType(),
                updated.curvDegL(),
                updated.curvDegR(),
                updated.curvDegT(),
                updated.curvDegB(),
                updated.tiltDegH(),
                updated.tiltDegV()));
    }

    /** 播放指定源到该屏幕（屏幕控制页等"本屏自身"入口，不做忙碌检查）。 */
    public static boolean playOn(UUID screenId, String url) {
        if (screenId == null || url == null || url.isEmpty()) return false;
        ClientNetworkHandlers.sendAction(ScreenActionPayload.play(screenId, url));
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null) {
            cfg.addHistory(url);
        }
        return true;
    }

    /**
     * 总设置入口：向所有屏幕的 owner 发送"播放申请"（服务端逐屏派发；owner 在聊天栏
     * 点「接受」后才会在该屏播放，点「拒绝」则不播；60 秒未响应自动过期）。
     *
     * <p>不做忙碌预检：任何屏幕忙碌都可以随时发送申请
     * （旧版"任一屏忙/队列非空则整体拒绝下发"的客户端检查已取消）；
     * 服务端仍是权威（无屏幕/权限/白名单校验都在服务端完成）。
     *
     * @return true = 申请已下发；false = 参数无效或没有屏幕
     */
    public static boolean playOnAll(String url) {
        if (url == null || url.isEmpty()) return false;
        List<CinemaScreen> all = new ArrayList<>(ClientScreenManager.get().allScreens().values());
        if (all.isEmpty()) {
            chat("§c[CinemaForYou] 还没有可播放的屏幕（先用选择器创建）");
            return false;
        }
        ClientNetworkHandlers.sendAction(ScreenActionPayload.playAll(url));
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null) {
            cfg.addHistory(url);
        }
        return true;
    }

    /** 聊天栏提示（全局播放入口共用）。 */
    static void chat(String msg) {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.sendSystemMessage(Component.literal(msg));
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

    /** 以屏幕中心线居中的灰色说明文字（置灰提示用）。 */
    private void addGrayLabel(String text, int cx, int y, int w) {
        addRenderableWidget(new GuiTextLabel(cx, y, w, 12, text,
                GuiTextLabel.Align.CENTER, GuiTextLabel.GRAY_LIGHT));
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
