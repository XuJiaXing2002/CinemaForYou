package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本地视频库：浏览 {@code cinema/videos}（及配置的本地视频目录）中的媒体文件，
 * 可【立即播放】到目标屏幕，或【加入该屏播放队列】供"自动播放下一个"使用。
 */
public class VideoLibraryScreen extends Screen {
    @Override
    public void onClose() {
        if (!GuiNav.back(this)) {
            super.onClose();
        }
    }


    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".avi",
        ".flv", ".wmv", ".ts", ".m4v", ".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac"};
    private static final int ROWS_PER_PAGE = 5;

    private final UUID screenId;
    private final List<File> files = new ArrayList<>();
    private int page = 0;
    private String query = "";
    private String pendingDelete = null;
    private EditBox searchBox;

    public VideoLibraryScreen(UUID screenId) {
        super(Component.literal("本地视频库"));
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        loadFiles();
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        int cx = this.width / 2;

        addRenderableWidget(Button.builder(
                Component.literal("§e📂 本地视频库 → 播放到屏幕"),
                btn -> {}
        ).bounds(cx - 155, 20, 310, 16).build()).active = false;

        // 搜索框
        searchBox = new EditBox(this.font, cx - 155, 44, 248, 20,
                Component.literal("搜索文件名"));
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.literal("🔍"),
                btn -> {
                    query = searchBox.getValue() == null ? "" : searchBox.getValue().trim();
                    page = 0;
                    rebuildWidgets();
                }
        ).bounds(cx + 97, 44, 58, 20).build());

        List<File> shown = new ArrayList<>();
        for (File f : files) {
            if (query.isEmpty() || f.getName().toLowerCase().contains(query.toLowerCase())) {
                shown.add(f);
            }
        }

        if (files.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§c无媒体文件 - 请放入 游戏目录/cinema/videos/"),
                    btn -> {}
            ).bounds(cx - 155, 72, 310, 20).build();
            addRenderableWidget(empty);
        } else if (shown.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§7没有匹配「" + query + "」的文件"),
                    btn -> {}
            ).bounds(cx - 155, 72, 310, 20).build();
            addRenderableWidget(empty);
        } else {
            int maxPage = (shown.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(shown.size(), start + ROWS_PER_PAGE);
            int y = 72;
            for (int i = start; i < end; i++) {
                File f = shown.get(i);
                String url = "file:" + f.getAbsolutePath().replace('\\', '/');
                String fullName = f.getName();
                String name = truncate(fullName, 24);
                addRenderableWidget(Button.builder(Component.literal(""),
                        btn -> {
                            ScreenSoundSettingsScreen.playOn(screenId, url);
                            onClose();
                        }
                ).bounds(cx - 155, y, 196, 20).build());
                // 文件名超宽时横向滚动显示全部，不省略
                addRenderableWidget(new MarqueeText(cx - 155, y, 196, 20,
                        "§a▶ " + fullName, 0xFFFFFFFF));
                addRenderableWidget(Button.builder(
                        Component.literal("＋队列"),
                        btn -> {
                            ClientConfig cfg = CinemaForYouClient.clientConfig;
                            if (cfg != null) cfg.addToQueue(screenId.toString(), url);
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                        "§a[CinemaForYou] 已加入队列: " + name
                                                + " §7（播完模式选「自动播放下一个」生效）"));
                            }
                        }
                ).bounds(cx + 45, y, 50, 20).build());
                addRenderableWidget(Button.builder(
                        Component.literal(f.getName().equals(pendingDelete)
                                ? "§c⚠确认?" : "✕删除"),
                        btn -> deleteLocalFile(f)
                ).bounds(cx + 99, y, 56, 20).build());
                y += 22;
            }

            // 统一分页条：与其它列表一致 [◀][p/t][▶]
            boolean hasPrev = page > 0;
            Button prev = Button.builder(Component.literal("§l◀"),
                    btn -> { page--; rebuildWidgets(); }
            ).bounds(cx - 155, this.height - 30, 40, 20).build();
            prev.active = hasPrev;
            addRenderableWidget(prev);
            Button pageLabel = Button.builder(
                    Component.literal("§7" + (page + 1) + "/" + (maxPage + 1)), btn -> {}
            ).bounds(cx - 110, this.height - 30, 44, 20).build();
            pageLabel.active = false;
            addRenderableWidget(pageLabel);
            boolean hasNext = page < maxPage;
            Button next = Button.builder(Component.literal("§l▶"),
                    btn -> { page++; rebuildWidgets(); }
            ).bounds(cx - 61, this.height - 30, 40, 20).build();
            next.active = hasNext;
            addRenderableWidget(next);
        }

        // 底部固定：返回按钮与分页条同行右侧（与其它列表页一致）
        addRenderableWidget(Button.builder(
                Component.literal("← 返回上一级"),
                btn -> onClose()
        ).bounds(cx - 155 + 140, this.height - 30, Math.max(60, 310 - 140), 20).build());
    }

    /** 删除本地视频文件（仅限本地视频目录内；两次点击确认，删除不可恢复）。 */
    private void deleteLocalFile(File f) {
        if (f == null) return;
        if (!f.getName().equals(pendingDelete)) {
            pendingDelete = f.getName();
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 将删除本地磁盘上的文件（不可恢复）: " + f.getName()
                            + " §7再点一次确认"));
            rebuildWidgets();
            return;
        }
        pendingDelete = null;
        try {
            Path dir = CinemaForYouClient.clientConfig != null
                    ? CinemaForYouClient.clientConfig.resolveVideosDir()
                    : Minecraft.getInstance().gameDirectory.toPath().resolve("cinema").resolve("videos");
            Path target = f.toPath().toAbsolutePath().normalize();
            Path root = dir.toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 拒绝删除本地视频目录之外的文件"));
                return;
            }
            if (java.nio.file.Files.deleteIfExists(target)) {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 已删除本地文件: " + f.getName()));
            } else {
                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                        "§c[CinemaForYou] 文件不存在: " + f.getName()));
            }
        } catch (Throwable t) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§c[CinemaForYou] 删除失败: " + t));
        }
        loadFiles();
        rebuildWidgets();
    }

    private void loadFiles() {
        files.clear();
        Path dir = CinemaForYouClient.clientConfig != null
                ? CinemaForYouClient.clientConfig.resolveVideosDir()
                : Minecraft.getInstance().gameDirectory.toPath().resolve("cinema").resolve("videos");        File dirFile = dir.toFile();
        if (dirFile.exists() && dirFile.isDirectory()) {
            File[] children = dirFile.listFiles();
            if (children != null) {
                java.util.Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
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
