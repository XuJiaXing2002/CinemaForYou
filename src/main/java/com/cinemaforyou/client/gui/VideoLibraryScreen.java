package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
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

    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".avi",
        ".flv", ".wmv", ".ts", ".m4v", ".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac"};
    private static final int ROWS_PER_PAGE = 5;

    private final UUID screenId;
    private final List<File> files = new ArrayList<>();
    private int page = 0;

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

        if (files.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§c无媒体文件 - 请放入 游戏目录/cinema/videos/"),
                    btn -> {}
            ).bounds(cx - 155, 48, 310, 20).build();
            addRenderableWidget(empty);
        } else {
            int maxPage = (files.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(files.size(), start + ROWS_PER_PAGE);
            int y = 48;
            for (int i = start; i < end; i++) {
                File f = files.get(i);
                String url = "file:" + f.getAbsolutePath().replace('\\', '/');
                String name = truncate(f.getName(), 30);
                addRenderableWidget(Button.builder(
                        Component.literal("§a▶ " + name),
                        btn -> {
                            ScreenSoundSettingsScreen.playOn(screenId, url);
                            onClose();
                        }
                ).bounds(cx - 155, y, 245, 20).build());
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
                ).bounds(cx + 95, y, 60, 20).build());
                y += 22;
            }

            boolean hasPrev = page > 0;
            Button prev = Button.builder(Component.literal("§l◀"),
                    btn -> { page--; rebuildWidgets(); }
            ).bounds(cx - 155, this.height - 30, 40, 20).build();
            prev.active = hasPrev;
            addRenderableWidget(prev);

            Button pageLabel = Button.builder(
                    Component.literal("§7" + (page + 1) + "/" + (maxPage + 1)), btn -> {}
            ).bounds(cx - 110, this.height - 30, 40, 20).build();
            addRenderableWidget(pageLabel);

            boolean hasNext = page < maxPage;
            Button next = Button.builder(Component.literal("§l▶"),
                    btn -> { page++; rebuildWidgets(); }
            ).bounds(cx - 65, this.height - 30, 40, 20).build();
            next.active = hasNext;
            addRenderableWidget(next);
        }

        // 返回声音与播放设置
        addRenderableWidget(Button.builder(
                Component.literal("← 返回设置"),
                btn -> Minecraft.getInstance().gui.setScreen(
                        new CinemaSettingsScreen())
        ).bounds(cx + 85, this.height - 30, 70, 20).build());
    }

    private void loadFiles() {
        files.clear();
        Path dir = CinemaForYouClient.clientConfig != null
                ? CinemaForYouClient.clientConfig.resolveVideosDir()
                : Minecraft.getInstance().gameDirectory.toPath().resolve("cinema").resolve("videos");
        File dirFile = dir.toFile();
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
