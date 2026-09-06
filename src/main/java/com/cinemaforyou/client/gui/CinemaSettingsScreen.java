package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.data.CinemaScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 影院设置界面（V 键主设置，可滚动）。
 *
 * <p>内容：渲染/音量/声音默认值、cookies（浏览器下拉选择 + cookies.txt 文件浏览）、
 * 本地视频目录，以及"目标屏幕"的播完行为、本地视频列表与播放历史入口。
 * 超出屏幕高度时可用滚轮 + 右侧滚动条滚动。
 */
public class CinemaSettingsScreen extends ScrollableSettingsScreen {

    private static final List<Integer> RENDER_DISTANCES = Arrays.asList(32, 64, 128, 256, 512);
    private static final List<Integer> VOLUMES = Arrays.asList(0, 20, 40, 60, 80, 100);
    private static final List<Integer> AUDIO_RANGES = Arrays.asList(32, 64, 128, 256, 512);
    private static final List<Integer> AUDIO_FALLOFFS_X10 = Arrays.asList(5, 10, 15, 20, 30);
    /** cookies 来源浏览器选项（空 = 不使用）。 */
    private static final List<String> BROWSERS = Arrays.asList(
            "", "edge", "chrome", "firefox", "brave", "vivaldi", "opera", "ie");

    // 工作副本（保存时才写回配置）
    private int renderDistance;
    private int volume;
    private int audioRange;
    private int audioFalloffX10;
    private boolean autoDownloadYtDlp;
    private boolean showSelectionBox;
    private boolean showDebugInfo;
    private String cookiesBrowser = "";
    private String cookiesFile = "";
    private String videosDir = "";

    private boolean browserOpen = false;
    private int cookiesBrowserRowY = 0;
    private boolean scrolledToPopup = false;
    private EditBox cookiesFileField;
    private EditBox videosDirField;

    public CinemaSettingsScreen() {
        super(Component.translatable("gui.cinemaforyou.settings.title"));
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null) {
            renderDistance = cfg.renderDistance;
            volume = cfg.defaultVolume;
            audioRange = cfg.audioMaxDistance;
            audioFalloffX10 = (int) Math.round(cfg.audioFalloffExponent * 10.0);
            autoDownloadYtDlp = cfg.autoDownloadYtDlp;
            showSelectionBox = cfg.showSelectionBox;
            showDebugInfo = cfg.showDebugInfo;
            cookiesBrowser = cfg.ytDlpCookiesFromBrowser == null ? "" : cfg.ytDlpCookiesFromBrowser;
            cookiesFile = cfg.ytDlpCookiesFile == null ? "" : cfg.ytDlpCookiesFile;
            videosDir = cfg.localVideosDir == null ? "" : cfg.localVideosDir;
        }
    }

    // ───────────── 滚动布局 ─────────────

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
        int left = cx - 155;   // 标签列
        int right = cx + 5;    // 控件列
        int labelW = 150, ctrlW = 150;
        int y = 8, rowH = 21;
        int maxW = Math.min(310, this.width - 20);
        if (!browserOpen) {
            scrolledToPopup = false;
        }

        addCenteredLabel("看着屏幕按 V 可打开该屏幕控制；本页设置全局默认值", cx, ry(y), maxW);
        y += rowH;

        // ── 渲染距离 ──
        addLabel("渲染距离", left, ry(y), labelW);
        addRenderableWidget(Button.builder(
                Component.literal(renderDistance + " 格（点击切换）"),
                btn -> {
                    int idx = Math.max(0, RENDER_DISTANCES.indexOf(renderDistance));
                    renderDistance = RENDER_DISTANCES.get((idx + 1) % RENDER_DISTANCES.size());
                    btn.setMessage(Component.literal(renderDistance + " 格（点击切换）"));
                }
        ).bounds(right, ry(y), ctrlW, 18).build());
        y += rowH;

        // ── 默认音量 ──
        addLabel("默认音量", left, ry(y), labelW);
        addRenderableWidget(Button.builder(
                Component.literal(volume + " %（点击切换）"),
                btn -> {
                    int idx = Math.max(0, VOLUMES.indexOf(volume));
                    volume = VOLUMES.get((idx + 1) % VOLUMES.size());
                    btn.setMessage(Component.literal(volume + " %（点击切换）"));
                }
        ).bounds(right, ry(y), ctrlW, 18).build());
        y += rowH;

        // ── 默认声音传播范围 ──
        addLabel("声音距离(默认)", left, ry(y), labelW);
        addRenderableWidget(Button.builder(
                Component.literal(audioRange + " 格（点击切换）"),
                btn -> {
                    int idx = Math.max(0, AUDIO_RANGES.indexOf(audioRange));
                    audioRange = AUDIO_RANGES.get((idx + 1) % AUDIO_RANGES.size());
                    btn.setMessage(Component.literal(audioRange + " 格（点击切换）"));
                }
        ).bounds(right, ry(y), ctrlW, 18).build());
        y += rowH;

        // ── 默认距离衰减 ──
        addLabel("距离衰减(默认)", left, ry(y), labelW);
        addRenderableWidget(Button.builder(
                Component.literal(falloffLabel(audioFalloffX10) + "（点击切换）"),
                btn -> {
                    int idx = Math.max(0, AUDIO_FALLOFFS_X10.indexOf(audioFalloffX10));
                    audioFalloffX10 = AUDIO_FALLOFFS_X10.get((idx + 1) % AUDIO_FALLOFFS_X10.size());
                    btn.setMessage(Component.literal(falloffLabel(audioFalloffX10) + "（点击切换）"));
                }
        ).bounds(right, ry(y), ctrlW, 18).build());
        y += rowH;

        // ── yt-dlp 自动下载 ──
        addLabel("yt-dlp 自动下载", left, ry(y), labelW);
        addRenderableWidget(toggleButton(right, ry(y), ctrlW, autoDownloadYtDlp, v -> autoDownloadYtDlp = v));
        y += rowH;

        // ── 选择预览框 / 调试信息 ──
        addLabel("对角点选择预览框", left, ry(y), labelW);
        addRenderableWidget(toggleButton(right, ry(y), ctrlW, showSelectionBox, v -> showSelectionBox = v));
        y += rowH;

        addLabel("调试信息（聊天栏）", left, ry(y), labelW);
        addRenderableWidget(toggleButton(right, ry(y), ctrlW, showDebugInfo, v -> showDebugInfo = v));
        y += rowH + 2;

        // ── cookies 浏览器（下拉选择） ──
        addLabel("cookies 来源浏览器", left, ry(y), labelW);
        addRenderableWidget(Button.builder(
                Component.literal(browserLabel(cookiesBrowser) + (browserOpen ? " ▴" : " ▾")),
                btn -> {
                    browserOpen = !browserOpen;
                    rebuildWidgets();
                }
        ).bounds(right, ry(y), ctrlW, 18).build());
        cookiesBrowserRowY = y;
        y += rowH;
        if (browserOpen) {
            // 向下展开并预留空间：下方控件整体下移，选项不会被遮挡、可正常点击
            int itemH = 15;
            int py = ry(y);
            for (String b : BROWSERS) {
                String label = browserLabel(b);
                addRenderableWidget(Button.builder(
                        Component.literal(label),
                        btn -> {
                            cookiesBrowser = b;
                            browserOpen = false;
                            rebuildWidgets();
                        }
                ).bounds(right, py, ctrlW, 13).build());
                py += itemH;
            }
            y += BROWSERS.size() * itemH; // 为展开的选项预留纵向空间
        }

        // ── cookies.txt 文件（可浏览选择） ──
        cookiesFileField = new EditBox(this.font, 200, 16,
                Component.translatable("gui.cinemaforyou.settings.cookies_file"));
        cookiesFileField.setX(right);
        cookiesFileField.setY(ry(y));
        cookiesFileField.setMaxLength(256);
        cookiesFileField.setValue(cookiesFile);
        cookiesFileField.setHint(Component.literal("如 cookies.txt（优先于浏览器）"));
        addRenderableWidget(cookiesFileField);
        addLabel("cookies.txt 文件", left, ry(y) + 1, labelW);
        addRenderableWidget(Button.builder(
                Component.literal("选择文件…"),
                btn -> chooseCookiesFile()
        ).bounds(cx - 155, ry(y) + 18, 150, 15).build());
        y += rowH + 18;

        // ── 本地视频目录（文件夹浏览） ──
        videosDirField = new EditBox(this.font, 200, 16,
                Component.translatable("gui.cinemaforyou.settings.videos_dir"));
        videosDirField.setX(right);
        videosDirField.setY(ry(y));
        videosDirField.setMaxLength(256);
        videosDirField.setValue(videosDir.isEmpty() ? "cinema/videos" : videosDir);
        videosDirField.setHint(Component.literal("cinema/videos 或 D:\\Videos"));
        addRenderableWidget(videosDirField);
        addLabel("本地视频目录(文件夹)", left, ry(y) + 1, labelW);
        addRenderableWidget(Button.builder(
                Component.literal("浏览文件夹…"),
                btn -> chooseVideosDir()
        ).bounds(cx - 155, ry(y) + 18, 150, 15).build());
        y += rowH + 20;

        // ── 屏幕播放管理（目标屏幕） ──
        addCenteredLabel("——— 屏幕播放管理（选目标屏幕） ———", cx, ry(y), Math.min(300, this.width - 30));
        y += rowH;
        List<CinemaScreen> screens = new ArrayList<>(ClientScreenManager.get().allScreens().values());
        screens.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        CinemaScreen target = resolveTarget(screens);
        String targetLabel = target == null
                ? "§7（尚无屏幕：先用选择器选两个角点创建）"
                : target.displayName() + " @ " + target.center().toShortString();
        Button targetBtn = Button.builder(
                Component.literal("目标屏幕: " + targetLabel),
                btn -> cycleTarget(screens)
        ).bounds(cx - Math.min(300, this.width - 30) / 2, ry(y),
                Math.min(300, this.width - 30), 20).build();
        if (target == null) targetBtn.active = false;
        addRenderableWidget(targetBtn);
        y += rowH;

        // ── 播完行为（作用于目标屏幕） ──
        int mode = 0;
        if (target != null && CinemaForYouClient.clientConfig != null) {
            mode = CinemaForYouClient.clientConfig.playModeFor(target.id().toString());
        }
        int fMode = mode;
        CinemaScreen fTarget = target;
        Button modeBtn = Button.builder(
                Component.literal("播完行为: " + modeLabel(fMode) + "（点击切换）"),
                btn -> {
                    if (fTarget == null || CinemaForYouClient.clientConfig == null) return;
                    int next = (CinemaForYouClient.clientConfig.playModeFor(fTarget.id().toString()) + 1) % 4;
                    CinemaForYouClient.clientConfig.setPlayMode(fTarget.id().toString(), next);
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                            "§7[CinemaForYou] 目标屏 §e" + fTarget.displayName()
                                    + "§7 播完行为: §a" + modeLabel(next)));
                    rebuildWidgets();
                }
        ).bounds(cx - Math.min(300, this.width - 30) / 2, ry(y),
                Math.min(300, this.width - 30), 20).build();
        modeBtn.active = target != null;
        addRenderableWidget(modeBtn);
        y += rowH;

        // ── 本地视频列表 / 播放历史 ──
        UUID targetId = target != null ? target.id() : null;
        int btnW = Math.min(300, this.width - 30) / 2 - 3;
        Button libBtn = Button.builder(
                Component.literal("📂 本地视频…"),
                btn -> {
                    if (targetId != null) {
                        rememberTarget(targetId);
                        Minecraft.getInstance().gui.setScreen(new VideoLibraryScreen(targetId));
                    }
                }
        ).bounds(cx - Math.min(300, this.width - 30) / 2, ry(y), btnW, 20).build();
        libBtn.active = target != null;
        addRenderableWidget(libBtn);

        Button histBtn = Button.builder(
                Component.literal("🕘 播放历史…"),
                btn -> {
                    if (targetId != null) {
                        rememberTarget(targetId);
                        Minecraft.getInstance().gui.setScreen(new HistoryScreen(targetId));
                    }
                }
        ).bounds(cx + 3, ry(y), btnW, 20).build();
        histBtn.active = target != null;
        addRenderableWidget(histBtn);
        y += rowH;

        Button serverLibBtn = Button.builder(
                Component.literal("🖥 服务器媒体库…（服务器 cinema/videos）"),
                btn -> {
                    if (targetId != null) {
                        rememberTarget(targetId);
                        Minecraft.getInstance().gui.setScreen(new ServerMediaScreen(targetId));
                    }
                }
        ).bounds(cx - Math.min(300, this.width - 30) / 2, ry(y),
                Math.min(300, this.width - 30), 20).build();
        serverLibBtn.active = target != null;
        addRenderableWidget(serverLibBtn);
        y += rowH;

        // 长提示自动换行完整显示
        int hintW = Math.min(320, this.width - 30);
        for (String line : UiText.wrap(
                "§7提示：播完行为选「自动播放下一个」时按该屏队列顺序循环；"
                        + "「循环本片」重复当前视频；历史会自动记录播放过的视频与链接。",
                hintW)) {
            addCenteredLabel(line, cx, ry(y), hintW);
            y += 11;
        }
        y += 2;

        // ── 保存 / 取消 ──
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cinemaforyou.settings.save"),
                btn -> onSave()
        ).bounds(cx - 155, ry(y), 150, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cinemaforyou.settings.cancel"),
                btn -> onClose()
        ).bounds(cx + 5, ry(y), 150, 20).build());
        y += 26;

        // 下拉展开后自动滚动，确保选项全部可见（只滚动一次）
        if (browserOpen && !scrolledToPopup) {
            scrolledToPopup = true;
            int popupBottom = cookiesBrowserRowY + rowH + BROWSERS.size() * 15;
            int minScroll = Math.max(0, popupBottom - (this.height - 6));
            if (scrollY < minScroll) {
                scrollY = minScroll;
                rebuildWidgets();
                return;
            }
        }
        finishContent(y);
    }

    // ───────────── 目标屏幕 ─────────────

    private void rememberTarget(UUID id) {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null && !id.toString().equals(cfg.lastTargetScreenId)) {
            cfg.lastTargetScreenId = id.toString();
            cfg.save();
        }
    }

    private CinemaScreen resolveTarget(List<CinemaScreen> screens) {
        if (screens.isEmpty()) return null;
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null && !cfg.lastTargetScreenId.isEmpty()) {
            for (CinemaScreen s : screens) {
                if (s.id().toString().equals(cfg.lastTargetScreenId)) {
                    return s;
                }
            }
        }
        return screens.get(0);
    }

    private void cycleTarget(List<CinemaScreen> screens) {
        if (screens.isEmpty()) return;
        CinemaScreen cur = resolveTarget(screens);
        int idx = 0;
        if (cur != null) {
            for (int i = 0; i < screens.size(); i++) {
                if (screens.get(i).id().equals(cur.id())) {
                    idx = i;
                    break;
                }
            }
        }
        CinemaScreen next = screens.get((idx + 1) % screens.size());
        rememberTarget(next.id());
        rebuildWidgets();
    }

    // ───────────── 辅助 ─────────────

    /** 左对齐说明文字（黄色高亮）。x = 左边缘，y = 文字顶部。 */
    private void addLabel(String text, int x, int y, int w) {
        addRenderableWidget(new GuiTextLabel(x, y + 3, w, 12, text,
                GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
    }

    /** 以屏幕中心线居中的文字（黄色）。x = 中心线坐标。 */
    private void addCenteredLabel(String text, int cx, int y, int w) {
        addRenderableWidget(new GuiTextLabel(cx, y, w, 12, text,
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
    }

    private Button toggleButton(int x, int y, int w, boolean initial,
                                java.util.function.Consumer<Boolean> setter) {
        boolean[] state = {initial};
        Button btn = Button.builder(
                Component.literal(state[0] ? "§a开" : "§c关"),
                b -> {
                    state[0] = !state[0];
                    setter.accept(state[0]);
                    b.setMessage(Component.literal(state[0] ? "§a开" : "§c关"));
                }
        ).bounds(x, y, w, 18).build();
        return btn;
    }

    private static String falloffLabel(int x10) {
        return switch (x10) {
            case 5 -> "缓 0.5";
            case 10 -> "线性 1.0";
            case 15 -> "较快 1.5";
            case 20 -> "快 2.0";
            case 30 -> "极快 3.0";
            default -> (x10 / 10.0) + "";
        };
    }

    private static String browserLabel(String b) {
        if (b == null || b.isEmpty()) return "（不使用 cookies）";
        return switch (b.toLowerCase()) {
            case "edge" -> "Edge";
            case "chrome" -> "Chrome";
            case "firefox" -> "Firefox";
            case "brave" -> "Brave";
            case "vivaldi" -> "Vivaldi";
            case "opera" -> "Opera";
            default -> b;
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

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 系统文件对话框（选择 cookies.txt）。Swing EDT，结果回主线程写回输入框。 */
    private void chooseCookiesFile() {
        var mc = Minecraft.getInstance();
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] headless 模式无法弹出文件窗口，请手动输入路径"));
            }
            return;
        }
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§7[CinemaForYou] 正在打开文件选择窗口（首次打开需数秒）…"));
        }
        SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("选择 cookies.txt（Netscape 格式）");
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                chooser.setAcceptAllFileFilterUsed(true);
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "cookies.txt / 文本文件 (*.txt)", "txt"));
                javax.swing.JDialog topParent = new javax.swing.JDialog();
                topParent.setAlwaysOnTop(true);
                topParent.setBounds(0, 0, 1, 1);
                int result = chooser.showOpenDialog(topParent);
                if (result == JFileChooser.APPROVE_OPTION) {
                    java.io.File sel = chooser.getSelectedFile();
                    if (sel != null) {
                        String path = sel.getAbsolutePath();
                        Minecraft.getInstance().execute(() -> {
                            cookiesFile = path;
                            if (cookiesFileField != null) {
                                cookiesFileField.setValue(path);
                            }
                            if (mc.player != null) {
                                mc.player.sendSystemMessage(Component.literal(
                                        "§a已选择 cookies 文件: " + path + " §7（记得点保存生效）"));
                            }
                        });
                    }
                }
            } catch (Throwable t) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                            "§c打开文件选择器失败: " + t + "（可直接手动输入路径）"));
                }
            }
        });
    }

    /** 系统目录选择对话框（选文件夹）。 */
    private void chooseVideosDir() {
        var mc = Minecraft.getInstance();
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] headless 模式无法弹出窗口，请手动输入路径"));
            }
            return;
        }
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§7[CinemaForYou] 正在打开目录选择窗口（只能选文件夹；首次打开需数秒）…"));
        }
        SwingUtilities.invokeLater(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("选择本地视频目录（文件夹）");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                javax.swing.JDialog topParent = new javax.swing.JDialog();
                topParent.setAlwaysOnTop(true);
                topParent.setBounds(0, 0, 1, 1);
                int result = chooser.showOpenDialog(topParent);
                if (result == JFileChooser.APPROVE_OPTION) {
                    java.io.File sel = chooser.getSelectedFile();
                    if (sel == null) {
                        Minecraft.getInstance().execute(() -> {
                            if (mc.player != null) {
                                mc.player.sendSystemMessage(Component.literal(
                                        "§c未选中目录（此对话框只选文件夹：单击进入后点“打开”）"));
                            }
                        });
                        return;
                    }
                    String path = sel.getAbsolutePath();
                    Minecraft.getInstance().execute(() -> {
                        videosDir = path;
                        if (videosDirField != null) {
                            videosDirField.setValue(path);
                        }
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(Component.literal(
                                    "§a已选择目录: " + path + " §7（记得点保存生效）"));
                        }
                    });
                }
            } catch (Throwable t) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                            "§c打开目录选择器失败: " + t));
                }
            }
        });
    }

    /** 写回配置文件并立即生效。 */
    private void onSave() {
        ClientConfig cfg = currentConfig();
        cfg.renderDistance = renderDistance;
        cfg.defaultVolume = volume;
        cfg.audioMaxDistance = audioRange;
        cfg.audioFalloffExponent = audioFalloffX10 / 10.0;
        cfg.autoDownloadYtDlp = autoDownloadYtDlp;
        cfg.showSelectionBox = showSelectionBox;
        cfg.showDebugInfo = showDebugInfo;
        cfg.ytDlpCookiesFromBrowser = cookiesBrowser;
        String cf = cookiesFileField != null ? cookiesFileField.getValue().trim() : "";
        cfg.ytDlpCookiesFile = cf;
        String vd = videosDirField != null ? videosDirField.getValue().trim() : "";
        cfg.localVideosDir = vd.isEmpty() ? "cinema/videos" : vd;
        cfg.save();
        onClose();
    }

    private ClientConfig currentConfig() {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        return cfg != null ? cfg : new ClientConfig();
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
