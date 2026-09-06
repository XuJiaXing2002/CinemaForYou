package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.video.VideoPlayer;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenOrientation;
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

        // ── 播放控制（两行等宽按钮，相对中心对齐下方控件） ──
        ScreenState state = currentState();
        String playPauseLabel = (state == ScreenState.PLAYING) ? "暂停" : "播放";
        int rowX = cx - (4 * 72 + 3 * 2) / 2;
        int[] x4 = {rowX, rowX + 74, rowX + 148, rowX + 222};
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

        // ── 显示设置（紧凑步进：-10 -1 值 +1 +10） ──
        y = stepRow(cx, y, "亮度", screen.brightnessPercent() + "%",
                (d, s) -> s.withSettings(clamp(s.brightnessPercent() + d, 0, 100),
                        s.volumePercent(), s.resolutionHeight(), s.displayScalePercent()));
        y = stepRow(cx, y, "音量", screen.volumePercent() + "%",
                (d, s) -> s.withSettings(s.brightnessPercent(),
                        clamp(s.volumePercent() + d, 0, 100), s.resolutionHeight(), s.displayScalePercent()));
        y = stepRow(cx, y, "大小", screen.displayScalePercent() + "%",
                (d, s) -> s.withSettings(s.brightnessPercent(), s.volumePercent(),
                        s.resolutionHeight(), clamp(s.displayScalePercent() + d, 25, 200)));
        y = stepRow(cx, y, "分辨率", screen.resolutionHeight() + "p",
                (d, s) -> s.withSettings(s.brightnessPercent(), s.volumePercent(),
                        resByIndex(s.resolutionHeight(), d), s.displayScalePercent()));

        // ── 曲面设置（每屏属性，0=平面） ──
        int curvType = screen.curvatureType();
        addRenderableWidget(Button.builder(
                Component.literal("曲率类型: " + curvatureTypeLabel(curvType)),
                btn -> {
                    int next = (curvType + 1) % 5;
                    updateSettings(s -> s.withCurvatureSettings(next,
                            s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB()));
                    if (next > 0) {
                        hint("曲率已开：用下方弧度调弯度；方向不对就再切一次类型（凸/凹互换）");
                    } else {
                        hint("已恢复平面屏");
                    }
                }
        ).bounds(cx - 105, ry(y), 210, 20).build());
        y += rowH;

        y = stepRow(cx, y, "左弧", screen.curvDegL() + "°",
                (d, s) -> s.withCurvatureSettings(s.curvatureType(),
                        clamp(s.curvDegL() + d, 0, 90), s.curvDegR(), s.curvDegT(), s.curvDegB()));
        y = stepRow(cx, y, "右弧", screen.curvDegR() + "°",
                (d, s) -> s.withCurvatureSettings(s.curvatureType(), s.curvDegL(),
                        clamp(s.curvDegR() + d, 0, 90), s.curvDegT(), s.curvDegB()));
        if (curvType == 3 || curvType == 4) {
            y = stepRow(cx, y, "上弧", screen.curvDegT() + "°",
                    (d, s) -> s.withCurvatureSettings(s.curvatureType(), s.curvDegL(), s.curvDegR(),
                            clamp(s.curvDegT() + d, 0, 90), s.curvDegB()));
            y = stepRow(cx, y, "下弧", screen.curvDegB() + "°",
                    (d, s) -> s.withCurvatureSettings(s.curvatureType(), s.curvDegL(), s.curvDegR(),
                            s.curvDegT(), clamp(s.curvDegB() + d, 0, 90)));
        }

        // ── 倾斜（绕屏幕自身旋转，即时生效） ──
        y = stepRow(cx, y, "左右倾斜", screen.tiltDegH() + "°",
                (d, s) -> s.withTiltSettings(clamp(s.tiltDegH() + d, -90, 90), s.tiltDegV()));
        y = stepRow(cx, y, "上下俯仰", screen.tiltDegV() + "°",
                (d, s) -> s.withTiltSettings(s.tiltDegH(), clamp(s.tiltDegV() + d, -90, 90)));

        // ── 移动屏幕（±1/±10 格，沿屏幕平面两轴，即时生效） ──
        int[] hDir = horizontalDelta(screen.orientation());
        int[] vDir = verticalDelta(screen.orientation());
        int[] nDir = normalDelta(screen.orientation());
        addRenderableWidget(new GuiTextLabel(cx, ry(y), 210, 12, "§e移动屏幕（横向）",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 13;
        y = moveButtons(cx, y, "横", "横", hDir);
        addRenderableWidget(new GuiTextLabel(cx, ry(y), 210, 12, "§e移动屏幕（纵向）",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 13;
        y = moveButtons(cx, y, "纵", "纵", vDir);
        addRenderableWidget(new GuiTextLabel(cx, ry(y), 210, 12, "§e移动屏幕（前后 · 垂直墙面）",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 13;
        y = moveButtons(cx, y, "后", "前", nDir);
        y += 2;

        // ── 屏幕边缘拉缩（四条边独立，外拉/内缩，即时生效） ──
        addRenderableWidget(new GuiTextLabel(cx, ry(y), 210, 12, "§e屏幕边缘拉缩（外拉扩大/内缩收小）",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 13;
        for (int edge = 0; edge < 4; edge++) {
            final int e = edge;
            addRenderableWidget(new GuiTextLabel(cx, ry(y), 210, 12, edgeName(edge) + "边",
                    GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 12;
            int ebw = 48, gap = 4;
            int x0 = cx - 105;
            addRenderableWidget(Button.builder(Component.literal("外10"),
                    b -> resizeScreenEdge(e, 10)).bounds(x0, ry(y), ebw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("外1"),
                    b -> resizeScreenEdge(e, 1)).bounds(x0 + ebw + gap, ry(y), ebw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("内1"),
                    b -> resizeScreenEdge(e, -1)).bounds(x0 + 2 * (ebw + gap), ry(y), ebw, 20).build());
            addRenderableWidget(Button.builder(Component.literal("内10"),
                    b -> resizeScreenEdge(e, -10)).bounds(x0 + 3 * (ebw + gap), ry(y), ebw, 20).build());
            y += 20;
        }
        y += 2;

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

    /**
     * 紧凑步进行：[-10][-1][ 名称 值 ][+1][+10] 单行微调/大跳。
     * applier 接收步长（±1/±10）与当前屏幕，返回新屏幕（含钳制）。
     */
    private int stepRow(int cx, int y, String label, String valueText,
                        java.util.function.BiFunction<Integer, CinemaScreen, CinemaScreen> applier) {
        int big = 36, small = 30, mid = 96, gap = 3;
        int total = big + gap + small + gap + mid + gap + small + gap + big;
        int x0 = cx - total / 2;
        int x1 = x0 + big + gap;
        int xMid = x1 + small + gap;
        int x3 = xMid + mid + gap;
        int x4 = x3 + small + gap;
        addRenderableWidget(Button.builder(
                Component.literal("§e" + label + " §f" + valueText), btn -> {})
                .bounds(xMid, ry(y), mid, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-10"),
                b -> updateSettings(s -> applier.apply(-10, s))).bounds(x0, ry(y), big, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-1"),
                b -> updateSettings(s -> applier.apply(-1, s))).bounds(x1, ry(y), small, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+1"),
                b -> updateSettings(s -> applier.apply(1, s))).bounds(x3, ry(y), small, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+10"),
                b -> updateSettings(s -> applier.apply(10, s))).bounds(x4, ry(y), big, 20).build());
        return y + 21;
    }

    /** 分辨率按档位列表前进/后退（±1/±10 档）。 */
    private static int resByIndex(int current, int step) {
        int idx = 0;
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (RESOLUTIONS[i] >= current) {
                idx = i;
                break;
            }
        }
        idx = clamp(idx + step, 0, RESOLUTIONS.length - 1);
        return RESOLUTIONS[idx];
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
                screen.audioFalloffTenths(),
                screen.curvatureType(),
                screen.curvDegL(),
                screen.curvDegR(),
                screen.curvDegT(),
                screen.curvDegB(),
                screen.tiltDegH(),
                screen.tiltDegV()));
    }

    private void updateSettings(java.util.function.UnaryOperator<CinemaScreen> updater) {
        CinemaScreen screen = currentScreen();
        if (screen == null) return;
        CinemaScreen updated = updater.apply(screen);
        // 乐观本地更新：数值/画面立即生效，服务端广播随后校准
        ClientScreenManager.get().localApplyScreen(updated);
        sendSettings(updated);
        Minecraft.getInstance().execute(this::rebuildWidgets);
    }

    // ───────────── 屏幕移动（±1/±10 格） ─────────────

    /** 一行四个移动按钮（-10/-1/+1/+10），vec 为该方向的单位向量。 */
    private int moveButtons(int cx, int y, String negLabel, String posLabel, int[] vec) {
        int bw = 48, gap = 4;
        int x0 = cx - 105;
        addRenderableWidget(Button.builder(Component.literal(negLabel + "-10"),
                b -> moveScreenBy(vec, -10)).bounds(x0, ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(negLabel + "-1"),
                b -> moveScreenBy(vec, -1)).bounds(x0 + bw + gap, ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(posLabel + "+1"),
                b -> moveScreenBy(vec, 1)).bounds(x0 + 2 * (bw + gap), ry(y), bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal(posLabel + "+10"),
                b -> moveScreenBy(vec, 10)).bounds(x0 + 3 * (bw + gap), ry(y), bw, 20).build());
        return y + 20;
    }

    private void moveScreenBy(int[] vec, int steps) {
        CinemaScreen s = currentScreen();
        if (s == null) return;
        int dx = vec[0] * steps, dy = vec[1] * steps, dz = vec[2] * steps;
        CinemaScreen moved = new CinemaScreen(s.id(),
                s.corner1().offset(dx, dy, dz), s.corner2().offset(dx, dy, dz),
                s.orientation(), s.sourceUrl(), s.ownerId(), s.createdAt(), s.customId(),
                s.brightnessPercent(), s.volumePercent(), s.resolutionHeight(), s.displayScalePercent(),
                s.audioRangeBlocks(), s.audioFalloffTenths(),
                s.curvatureType(), s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB(),
                s.tiltDegH(), s.tiltDegV());
        ClientScreenManager.get().localApplyScreen(moved);
        ClientNetworkHandlers.sendMove(screenId, dx, dy, dz);
        Minecraft.getInstance().execute(this::rebuildWidgets);
    }

    /** 屏幕平面横向单位向量（世界坐标方向）。 */
    private static int[] horizontalDelta(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{1, 0, 0};
            case AXIS_X -> new int[]{0, 0, 1};
            case AXIS_Y -> new int[]{1, 0, 0};
        };
    }

    /** 屏幕平面纵向单位向量（世界坐标方向）。 */
    private static int[] verticalDelta(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{0, 1, 0};
            case AXIS_X -> new int[]{0, 1, 0};
            case AXIS_Y -> new int[]{0, 0, 1};
        };
    }

    /** 屏幕法线方向（垂直墙面，正 = 离开墙面/朝向观众侧）。 */
    private static int[] normalDelta(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{0, 0, 1};
            case AXIS_X -> new int[]{1, 0, 0};
            case AXIS_Y -> new int[]{0, 1, 0};
        };
    }

    // ───────────── 屏幕边缘拉缩（四条边独立） ─────────────

    private static String edgeName(int edge) {
        return switch (edge) {
            case 0 -> "左";
            case 1 -> "右";
            case 2 -> "上";
            default -> "下";
        };
    }

    /** 屏幕平面两轴（横向 a / 纵向 b）。 */
    private static int[] planeAxes(ScreenOrientation o) {
        return switch (o) {
            case AXIS_Z -> new int[]{0, 1};
            case AXIS_X -> new int[]{2, 1};
            case AXIS_Y -> new int[]{0, 2};
        };
    }

    /**
     * 单边拉缩：edge 0=左 1=右 2=上 3=下；step>0 外拉扩大，<0 内缩收小。
     * 处于该边的角点沿世界轴移动，另一侧角点不动。
     */
    private void resizeScreenEdge(int edge, int step) {
        CinemaScreen s = currentScreen();
        if (s == null) return;
        int[] axes = planeAxes(s.orientation());
        int axis = (edge == 0 || edge == 1) ? axes[0] : axes[1];
        int a1 = axisValue(s.corner1(), axis);
        int a2 = axisValue(s.corner2(), axis);
        int minV = Math.min(a1, a2);
        int maxV = Math.max(a1, a2);
        boolean onMaxSide = edge == 1 || edge == 2;   // 右/上 边在 max 端
        int edgeVal = onMaxSide ? maxV : minV;
        int outSign = (edge == 0 || edge == 3) ? -1 : 1; // 外拉的世界坐标方向
        int delta = outSign * step;
        int[] dd1 = axisDelta(axis, 0);
        int[] dd2 = axisDelta(axis, 0);
        if (a1 == edgeVal) dd1 = axisDelta(axis, delta);
        if (a2 == edgeVal) dd2 = axisDelta(axis, delta);
        CinemaScreen moved = new CinemaScreen(s.id(),
                s.corner1().offset(dd1[0], dd1[1], dd1[2]),
                s.corner2().offset(dd2[0], dd2[1], dd2[2]),
                s.orientation(), s.sourceUrl(), s.ownerId(), s.createdAt(), s.customId(),
                s.brightnessPercent(), s.volumePercent(), s.resolutionHeight(), s.displayScalePercent(),
                s.audioRangeBlocks(), s.audioFalloffTenths(),
                s.curvatureType(), s.curvDegL(), s.curvDegR(), s.curvDegT(), s.curvDegB(),
                s.tiltDegH(), s.tiltDegV());
        ClientScreenManager.get().localApplyScreen(moved);
        ClientNetworkHandlers.sendResize(screenId,
                dd1[0], dd1[1], dd1[2], dd2[0], dd2[1], dd2[2]);
        Minecraft.getInstance().execute(this::rebuildWidgets);
    }

    private static int axisValue(net.minecraft.core.BlockPos p, int axis) {
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
            case 1 -> "水平凸弧";
            case 2 -> "水平凹弧";
            case 3 -> "双向凸弧(球面)";
            case 4 -> "双向凹弧(球面)";
            default -> "平面";
        };
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
