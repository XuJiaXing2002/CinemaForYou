package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.List;

/**
 * 影院设置界面（V 键主设置，可滚动）。
 *
 * <p>内容：渲染/音量/声音默认值、cookies（浏览器下拉选择 + cookies.txt 文件浏览）、
 * 本地视频目录，以及播完行为与本地视频/播放历史/服务器媒体库/播放队列入口。
 *
 * <p>本页所有播放入口不再针对单个目标屏幕：播放会对所有已存在的屏幕一起下发
 * （首次点击提示确认，再点一次才真正播放；任一屏幕忙碌则整体不下发）。
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
        // OP 权限（等级 ≥2）：以下涉及"屏幕/播放"的入口仅 OP 可操作（保存/取消不受限）
        boolean isOp = hasOpPermission();
        int cx = this.width / 2;
        // 整行宽度控件几何：所有长条按钮/输入框共用，保证左右边界对齐
        int blockW = Math.min(300, this.width - 30);
        int blockX = cx - blockW / 2;
        // 内容起始 y=2：首行说明文字贴屏幕顶部，去掉原来的顶部留白（行距保持不变）
        int y = 2, rowH = 21;
        int maxW = Math.min(310, this.width - 20);
        if (!browserOpen) {
            scrolledToPopup = false;
        }

        for (String line : UiText.wrap(
                "看着屏幕按 V 打开该屏幕控制；按 Shift+V 可选择某个屏幕的控制（本页为全局默认值）",
                maxW)) {
            addCenteredLabel(line, cx, ry(y), maxW);
            y += 11;
        }
        y += rowH - 11;

        // ── 顶部全局设置项（前 5 个）：与下方长条按钮同 x、同宽（整行宽度）、同高（20），行距 21；
        //    原「左侧黄色标题 + 右侧短按钮」两列布局已取消，标题并入按钮文案：功能名: 当前值（点击…） ──

        // ── 渲染距离（全局默认值） ──
        addRenderableWidget(Button.builder(
                Component.literal("渲染距离(全局): " + renderDistance + " 格（点击切换）"),
                btn -> {
                    int idx = Math.max(0, RENDER_DISTANCES.indexOf(renderDistance));
                    renderDistance = RENDER_DISTANCES.get((idx + 1) % RENDER_DISTANCES.size());
                    btn.setMessage(Component.literal("渲染距离(全局): " + renderDistance + " 格（点击切换）"));
                }
        ).bounds(blockX, ry(y), blockW, 20).build());
        y += rowH;

        // ── 默认音量（全局默认值） ──
        addRenderableWidget(Button.builder(
                Component.literal("默认音量(全局): " + volume + " %（点击切换）"),
                btn -> {
                    int idx = Math.max(0, VOLUMES.indexOf(volume));
                    volume = VOLUMES.get((idx + 1) % VOLUMES.size());
                    btn.setMessage(Component.literal("默认音量(全局): " + volume + " %（点击切换）"));
                }
        ).bounds(blockX, ry(y), blockW, 20).build());
        y += rowH;

        // ── 默认声音传播范围（全局默认值） ──
        addRenderableWidget(Button.builder(
                Component.literal("声音距离(全局): " + audioRange + " 格（点击切换）"),
                btn -> {
                    int idx = Math.max(0, AUDIO_RANGES.indexOf(audioRange));
                    audioRange = AUDIO_RANGES.get((idx + 1) % AUDIO_RANGES.size());
                    btn.setMessage(Component.literal("声音距离(全局): " + audioRange + " 格（点击切换）"));
                }
        ).bounds(blockX, ry(y), blockW, 20).build());
        y += rowH;

        // ── 默认距离衰减（全局默认值） ──
        addRenderableWidget(Button.builder(
                Component.literal("距离衰减(全局): " + falloffLabel(audioFalloffX10) + "（点击切换）"),
                btn -> {
                    int idx = Math.max(0, AUDIO_FALLOFFS_X10.indexOf(audioFalloffX10));
                    audioFalloffX10 = AUDIO_FALLOFFS_X10.get((idx + 1) % AUDIO_FALLOFFS_X10.size());
                    btn.setMessage(Component.literal("距离衰减(全局): "
                            + falloffLabel(audioFalloffX10) + "（点击切换）"));
                }
        ).bounds(blockX, ry(y), blockW, 20).build());
        y += rowH;

        // ── 默认音频延迟补偿（全局默认值；点击打开输入框） ──
        addRenderableWidget(Button.builder(
                Component.literal("音频延迟(全局): " + globalLatencyLabel() + "（点击修改）"),
                btn -> openGlobalLatencyEditor()
        ).bounds(blockX, ry(y), blockW, 20).build());
        y += rowH;

        // ── 播完行为（全局默认，对所有屏幕生效；每屏可在声音与播放设置里单独覆盖/跟随） ──
        final int[] gMode = {0};
        if (CinemaForYouClient.clientConfig != null) {
            gMode[0] = CinemaForYouClient.clientConfig.defaultPlayMode;
        }
        Button modeBtn = Button.builder(
                Component.literal("播完行为(全局默认): " + modeLabel(gMode[0]) + "（点击切换）"),
                btn -> {
                    if (CinemaForYouClient.clientConfig == null) return;
                    int next = (CinemaForYouClient.clientConfig.defaultPlayMode + 1) % 4;
                    CinemaForYouClient.clientConfig.defaultPlayMode = next;
                    CinemaForYouClient.clientConfig.save();
                    Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                            "§7[CinemaForYou] 播完行为全局默认: §a" + modeLabel(next)
                                    + " §7（对所有屏幕生效；某屏选「跟随全局」的屏幕按此执行）"));
                    rebuildWidgets();
                }
        ).bounds(cx - Math.min(300, this.width - 30) / 2, ry(y),
                Math.min(300, this.width - 30), 20).build();
        modeBtn.active = isOp;
        addRenderableWidget(modeBtn);
        y += rowH;

        // ── yt-dlp 网络代理（TikTok 等直连不通的站点用）：播放行为区块内、排在已有控件下方 ──
        int behaviorW = Math.min(300, this.width - 30);
        String proxyVal = CinemaForYouClient.clientConfig != null
                ? CinemaForYouClient.clientConfig.ytDlpProxy : "";
        final String[] proxyRef = {proxyVal};
        Button proxyBtn = Button.builder(
                Component.literal("网络代理(yt-dlp): "
                        + (proxyRef[0] == null || proxyRef[0].isEmpty()
                            ? "§7直连（TikTok 等被墙站点需填代理）"
                            : "§a" + proxyRef[0])),
                btn -> {
                    if (CinemaForYouClient.clientConfig == null) return;
                    openChild(new InputValueScreen(
                            "yt-dlp 代理地址（留空=直连）",
                            proxyRef[0],
                            120,
                            v -> {
                                CinemaForYouClient.clientConfig.ytDlpProxy = v == null ? "" : v.trim();
                                CinemaForYouClient.clientConfig.save();
                                proxyRef[0] = CinemaForYouClient.clientConfig.ytDlpProxy;
                            }));
                }
        ).bounds(cx - behaviorW / 2, ry(y), behaviorW, 20).build();
        addRenderableWidget(proxyBtn);
        y += rowH;   // 原下方"例: http://127.0.0.1:7890…"提示行已删除，后续控件相应上移

        // ── 本地视频列表 / 播放历史 / 服务器媒体库（总设置入口：播放到所有屏幕） ──
        int blockW2 = Math.min(300, this.width - 30);
        int blockX2 = cx - blockW2 / 2;
        int btnW = blockW2 / 2 - 3;
        Button libBtn = Button.builder(
                Component.literal("📂 本地视频…"),
                btn -> openChild(new VideoLibraryScreen())
        ).bounds(blockX2, ry(y), btnW, 20).build();
        libBtn.active = isOp;
        addRenderableWidget(libBtn);

        Button histBtn = Button.builder(
                Component.literal("🕘 播放历史…"),
                btn -> openChild(new HistoryScreen())
        ).bounds(cx + 3, ry(y), btnW, 20).build();
        histBtn.active = isOp;
        addRenderableWidget(histBtn);
        y += rowH;

        Button serverLibBtn = Button.builder(
                Component.literal("🖥 服务器媒体库…（服务器 cinema/videos）"),
                btn -> openChild(new ServerMediaScreen())
        ).bounds(blockX2, ry(y), blockW2, 20).build();
        serverLibBtn.active = isOp;
        addRenderableWidget(serverLibBtn);
        y += rowH;

        // ── 全局播放队列（不参与自动连播；手动点击条目才向所有屏幕发播放申请） ──
        Button queueBtn = Button.builder(
                Component.literal("全局播放队列管理"),
                btn -> openChild(new ScreenQueueManagerScreen())
        ).bounds(blockX2, ry(y), blockW2, 20).build();
        queueBtn.active = isOp;
        addRenderableWidget(queueBtn);
        y += rowH;

        // ── 屏幕列表管理（搜索/改名/描述/删除，仅创建者可操作） ──
        Button adminBtn = Button.builder(
                Component.literal("🖥 屏幕列表管理…（搜索/改名/描述/删除）"),
                btn -> openChild(new ScreenAdminListScreen())
        ).bounds(cx - Math.min(300, this.width - 30) / 2, ry(y),
                Math.min(300, this.width - 30), 20).build();
        addRenderableWidget(adminBtn);
        y += rowH;

        // ── cookies / 本地视频目录（整行宽度的长条按钮 + 等宽输入框）：排在常用入口组下方、3 个开关按钮上方 ──
        // 与上方所有长条按钮同 x、同宽（= 整行宽度）、同高

        // ① 长条按钮「选择cookies文件」：点击直接弹出系统"选择文件"对话框
        addRenderableWidget(Button.builder(
                Component.literal("选择cookies文件"),
                btn -> chooseCookiesFile()
        ).bounds(blockX, ry(y), blockW, 20).build());
        y += 22;

        // ② cookies 文件路径输入框：与上方按钮同 x、同宽，紧贴其下
        cookiesFileField = new EditBox(this.font, blockW, 18,
                Component.translatable("gui.cinemaforyou.settings.cookies_file"));
        cookiesFileField.setX(blockX);
        cookiesFileField.setY(ry(y));
        cookiesFileField.setMaxLength(256);
        cookiesFileField.setValue(cookiesFile);
        cookiesFileField.setHint(Component.literal("如 cookies.txt（优先于浏览器）"));
        addRenderableWidget(cookiesFileField);
        y += 20;

        // ③ 长条按钮「浏览本地文件夹」：点击直接弹出系统"选择文件夹"对话框
        addRenderableWidget(Button.builder(
                Component.literal("浏览本地文件夹"),
                btn -> chooseVideosDir()
        ).bounds(blockX, ry(y), blockW, 20).build());
        y += 22;

        // ④ 本地视频目录输入框：与上方按钮同 x、同宽，紧贴其下
        videosDirField = new EditBox(this.font, blockW, 18,
                Component.translatable("gui.cinemaforyou.settings.videos_dir"));
        videosDirField.setX(blockX);
        videosDirField.setY(ry(y));
        videosDirField.setMaxLength(256);
        videosDirField.setValue(videosDir.isEmpty() ? "cinema/videos" : videosDir);
        videosDirField.setHint(Component.literal("cinema/videos 或 D:\\Videos"));
        addRenderableWidget(videosDirField);
        y += 20;

        // ⑤ cookies来源浏览器：仍是下拉选项（点击弹出浏览器列表），长条按钮样式，无输入框
        addRenderableWidget(Button.builder(
                Component.literal("cookies来源浏览器: " + browserLabel(cookiesBrowser)
                        + (browserOpen ? " ▴" : " ▾")),
                btn -> {
                    browserOpen = !browserOpen;
                    rebuildWidgets();
                }
        ).bounds(blockX, ry(y), blockW, 20).build());
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
                ).bounds(blockX, py, blockW, 13).build());
                py += itemH;
            }
            y += BROWSERS.size() * itemH; // 为展开的选项预留纵向空间
        }

        // ── yt-dlp 自动下载（需要 OP） / 选择预览框 / 调试信息：这三个开关整组下移到 cookies 区块之后 ──
        Button ytDlpBtn = toggleButton(blockX, ry(y), blockW, "yt-dlp 自动下载",
                autoDownloadYtDlp, v -> autoDownloadYtDlp = v);
        if (!isOp) {
            ytDlpBtn.active = false;
            ytDlpBtn.setMessage(Component.literal("yt-dlp 自动下载: §7需要OP权限"));
        }
        addRenderableWidget(ytDlpBtn);
        y += rowH;

        // ── 选择预览框 / 调试信息 ──
        addRenderableWidget(toggleButton(blockX, ry(y), blockW, "对角点选择预览框",
                showSelectionBox, v -> showSelectionBox = v));
        y += rowH;

        addRenderableWidget(toggleButton(blockX, ry(y), blockW, "调试信息（聊天栏）",
                showDebugInfo, v -> showDebugInfo = v));
        y += rowH;

        y += 6;   // 最后一个内容按钮（调试信息）→ 保存/取消 的额外间距（保持原底部 7px，内容总高不变）

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

    // ───────────── 权限 ─────────────

    /**
     * 当前玩家是否 OP（权限等级 ≥2）。
     *
     * <p>26.2 权限 API 已重构：客户端等价于旧版 {@code player.hasPermissions(2)} 的写法是
     * {@code player.permissions().hasPermission(new Permission.HasCommandLevel(GAMEMASTERS))}
     * （客户端权限集由服务端同步，与服务端 {@code canControl} 判定一致）。
     */
    private static boolean hasOpPermission() {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.permissions().hasPermission(
                new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
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

    /** 整行宽度的开关按钮：文案「功能名: 开/关（点击切换）」，点击原地切换（不改动其它设置项）。 */
    private Button toggleButton(int x, int y, int w, String name, boolean initial,
                                java.util.function.Consumer<Boolean> setter) {
        boolean[] state = {initial};
        Button btn = Button.builder(
                Component.literal(name + ": " + (state[0] ? "§a开" : "§c关") + "（点击切换）"),
                b -> {
                    state[0] = !state[0];
                    setter.accept(state[0]);
                    b.setMessage(Component.literal(name + ": "
                            + (state[0] ? "§a开" : "§c关") + "（点击切换）"));
                }
        ).bounds(x, y, w, 20).build();
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

    /** 全局音频延迟按钮文案。 */
    private String globalLatencyLabel() {
        int ms = 0;
        if (CinemaForYouClient.clientConfig != null) {
            ms = (int) Math.max(0, Math.min(500, CinemaForYouClient.clientConfig.audioDeviceLatencyMs));
        }
        return ms == 0 ? "0ms（不补偿）" : ms + "ms";
    }

    /** 打开全局音频延迟输入框（0-500ms）。 */
    private void openGlobalLatencyEditor() {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg == null) return;
        int cur = (int) Math.max(0, Math.min(500, cfg.audioDeviceLatencyMs));
        openChild(new InputValueScreen(
                "全局音频延迟补偿（0-500ms）\n声音比画面慢→调大；画面比声音慢→调小；0=不补偿",
                String.valueOf(cur), 6,
                v -> {
                    try {
                        int ms = Integer.parseInt(v.trim());
                        cfg.audioDeviceLatencyMs = Math.max(0, Math.min(500, ms));
                        cfg.save();
                    } catch (Exception ignored) {
                        // 输入非法：保持原值
                    }
                }));
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
