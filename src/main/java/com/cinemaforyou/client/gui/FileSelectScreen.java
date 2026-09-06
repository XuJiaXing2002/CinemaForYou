package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.network.CreateScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 原版风格本地文件选择屏（纯组件实现）。
 *
 * <p>列出 {@code <游戏目录>/cinema/videos/} 下的视频文件（.mp4/.mkv/.webm/.mov 等），
 * 每个文件一个按钮，点击即创建屏幕并播放。附带可选自定义 ID 输入框。
 *
 * <p>渲染全部走 {@code addRenderableWidget} 组件路径（26.2 中
 * {@code extractor.text} 自定义绘制不可靠、{@code addWidget} 不渲染）。
 */
public class FileSelectScreen extends Screen {

    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".avi",
        ".flv", ".wmv", ".ts", ".m4v", ".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac"};
    private static final int ROWS_PER_PAGE = 6;

    private final BlockPos corner1;
    private final BlockPos corner2;
    private final List<File> files = new ArrayList<>();
    private int page = 0;

    private EditBox nameField;

    public FileSelectScreen(BlockPos corner1, BlockPos corner2) {
        super(Component.translatable("gui.cinemaforyou.file_select.title"));
        this.corner1 = corner1;
        this.corner2 = corner2;
        // 从 URL 输入屏跳转过来时 removed() 已清预览，这里恢复黄色范围框
        com.cinemaforyou.client.render.SelectionPreview.beginConfirming(corner1, corner2);
    }

    @Override
    protected void init() {
        loadFiles();
        rebuildWidgets();
    }

    /** 覆盖原版 rebuildWidgets：按当前 page 重建文件列表按钮（翻页时调用）。 */
    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        int cx = this.width / 2;

        // 自定义 ID 输入框
        nameField = new EditBox(this.font, 200, 16,
                Component.translatable("gui.cinemaforyou.url_input.name"));
        nameField.setX(cx - 100);
        nameField.setY(24);
        nameField.setMaxLength(32);
        nameField.setHint(Component.literal("自定义 ID（可留空）"));
        addRenderableWidget(nameField);

        if (files.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§c无视频文件 - 请放入 游戏目录/cinema/videos/"),
                    btn -> {}
            ).bounds(cx - 155, 60, 310, 20).build();
            empty.active = false;
            addRenderableWidget(empty);
        } else {
            int maxPage = (files.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(files.size(), start + ROWS_PER_PAGE);
            for (int i = start; i < end; i++) {
                File f = files.get(i);
                int row = i - start;
                addRenderableWidget(Button.builder(
                        Component.literal("§a▶ " + f.getName()),
                        btn -> playFile(f)
                ).bounds(cx - 150, 48 + row * 22, 300, 20).build());
            }

            // 翻页按钮
            boolean hasPrev = page > 0;
            Button prev = Button.builder(
                    Component.literal("§l◀"),
                    btn -> { page--; rebuildWidgets(); }
            ).bounds(cx - 150, this.height - 54, 40, 20).build();
            prev.active = hasPrev;
            addRenderableWidget(prev);

            Button pageLabel = Button.builder(
                    Component.literal("§7" + (page + 1) + "/" + (maxPage + 1)),
                    btn -> {}
            ).bounds(cx - 105, this.height - 54, 40, 20).build();
            pageLabel.active = false;
            addRenderableWidget(pageLabel);

            boolean hasNext = page < maxPage;
            Button next = Button.builder(
                    Component.literal("§l▶"),
                    btn -> { page++; rebuildWidgets(); }
            ).bounds(cx - 60, this.height - 54, 40, 20).build();
            next.active = hasNext;
            addRenderableWidget(next);
        }

        // 返回按钮
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cinemaforyou.file_select.back"),
                btn -> Minecraft.getInstance().gui.setScreen(
                        new UrlInputScreen(corner1, corner2))
        ).bounds(cx + 40, this.height - 54, 110, 20).build());

        // 系统文件选择器（可浏览任意目录的视频文件）
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cinemaforyou.file_select.system"),
                btn -> openSystemChooser()
        ).bounds(cx - 150, this.height - 78, 300, 20).build());
    }

    /**
     * 打开系统原生文件选择对话框（AWT FileDialog，原生 Windows 资源管理器窗口）。
     *
     * <p>不再使用 Swing JFileChooser：它首次初始化需扫描全盘（数秒无任何反应）、
     * 且对话框容易被全屏游戏窗口遮挡。原生 FileDialog 弹出快、总能置顶获得焦点。
     * 起始目录为客户端配置的 {@code localVideosDir}，选中文件后回到主线程发包。
     */
    private void openSystemChooser() {
        var mc = Minecraft.getInstance();
        // headless 环境下 AWT 对话框必然抛 HeadlessException
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] Java 运行于 headless 模式，无法弹出文件窗口。"
                                + "请在启动器 JVM 参数中加入 -Djava.awt.headless=false 后重启"));
            }
            return;
        }
        // 立即给聊天反馈，用户知道点击已生效
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(
                    "§7[CinemaForYou] 正在打开文件选择窗口（首次打开需数秒，请稍候）…"));
        }

        java.io.File startDir = null;
        var cfg = com.cinemaforyou.CinemaForYouClient.clientConfig;
        if (cfg != null) {
            java.nio.file.Path dir = cfg.resolveVideosDir();
            java.io.File f = dir.toFile();
            startDir = f.exists() ? f : null;
        }
        final java.io.File finalStart = startDir;
        final String customId = (nameField != null) ? nameField.getValue().trim() : "";

        java.awt.EventQueue.invokeLater(() -> {
            try {
                // Swing JFileChooser：经用户环境验证可正常显示文件列表；
                // 原生 FileDialog 在此环境会显示空白文件列表，弃用。
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(finalStart);
                chooser.setDialogTitle("选择视频/音频文件");
                chooser.setFileSelectionMode(javax.swing.JFileChooser.FILES_ONLY);
                chooser.setFileHidingEnabled(false);
                chooser.setAcceptAllFileFilterUsed(true);
                chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                        "媒体文件 (mp4/mkv/webm/mov/avi/flv/wmv/ts/m4v/mp3/m4a/wav/flac/ogg)",
                        "mp4", "mkv", "webm", "mov", "avi", "flv", "wmv", "ts", "m4v",
                        "mp3", "m4a", "wav", "flac", "ogg", "aac"));
                // 置顶空父窗口：防止对话框被全屏的 Minecraft 窗口挡住
                javax.swing.JDialog topParent = new javax.swing.JDialog();
                topParent.setAlwaysOnTop(true);
                topParent.setBounds(0, 0, 1, 1);
                int result = chooser.showOpenDialog(topParent);

                java.io.File selected =
                        (result == javax.swing.JFileChooser.APPROVE_OPTION)
                                ? chooser.getSelectedFile() : null;
                if (selected != null) {
                    String name = selected.getName();
                    if (!isVideoFile(name)) {
                        if (mc.player != null) {
                            mc.player.sendSystemMessage(Component.literal(
                                    "§c[CinemaForYou] 不是支持的媒体格式（mp4/mkv/webm/mov/avi/flv/wmv/ts/m4v/mp3/m4a/wav/flac/ogg）: "
                                            + name));
                        }
                        return;
                    }
                    String url = "file:" + selected.getAbsolutePath().replace('\\', '/');
                    // 回到主线程发包并关界面
                    Minecraft.getInstance().execute(() -> {
                        ClientNetworkHandlers.sendCreateScreen(
                                new CreateScreenPayload(corner1, corner2, url, customId));
                        onClose();
                    });
                }
            } catch (Throwable t) {
                if (mc.player != null) {
                    mc.player.sendSystemMessage(Component.literal(
                            "§c[CinemaForYou] 打开文件选择器失败: " + t));
                }
            }
        });
    }

    private void playFile(File f) {
        String url = "file:" + f.getAbsolutePath().replace('\\', '/');
        ClientNetworkHandlers.sendCreateScreen(new CreateScreenPayload(
                corner1, corner2, url, nameField != null ? nameField.getValue().trim() : ""));
        onClose();
    }

    private void loadFiles() {
        files.clear();
        Path dir = com.cinemaforyou.CinemaForYouClient.clientConfig != null
                ? com.cinemaforyou.CinemaForYouClient.clientConfig.resolveVideosDir()
                : Minecraft.getInstance().gameDirectory.toPath().resolve("cinema").resolve("videos");
        File dirFile = dir.toFile();
        if (dirFile.exists() && dirFile.isDirectory()) {
            File[] children = dirFile.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (f.isFile() && isVideoFile(f.getName())) {
                        files.add(f);
                    }
                }
            }
        }
    }

    private boolean isVideoFile(String name) {
        String lower = name.toLowerCase();
        for (String ext : VIDEO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** 覆盖默认背景：默认渲染菜单全景图，游戏内改画半透明深色底。 */
    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.fill(0, 0, this.width, this.height, 0x90101014);
    }

    /** 界面关闭时清除黄色范围预览框（返回 URL 输入屏时其构造器会重新设置）。 */
    @Override
    public void removed() {
        super.removed();
        com.cinemaforyou.client.render.SelectionPreview.clear();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
