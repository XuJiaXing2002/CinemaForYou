package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.network.MediaLibraryClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 服务器媒体库：列出 服务器目录/cinema/videos/ 下的媒体文件。
 * 点 ▶ 播放到目标屏幕；＋队列 加入该屏播放队列；支持刷新。
 */
public class ServerMediaScreen extends Screen {
    @Override
    public void onClose() {
        if (!GuiNav.back(this)) {
            super.onClose();
        }
    }


    private static final int ROWS_PER_PAGE = 4;

    private final UUID screenId;
    private int page = 0;
    private boolean loading = true;
    private String pendingDelete = null;

    public ServerMediaScreen(UUID screenId) {
        super(Component.literal("服务器媒体库"));
        this.screenId = screenId;
    }

    @Override
    protected void init() {
        MediaLibraryClient.setListener(files -> {
            loading = false;
            rebuildWidgets();
        });
        MediaLibraryClient.setMetaListener(() -> {
            loading = false;
            rebuildWidgets();
        });
        refresh();
    }

    @Override
    public void removed() {
        super.removed();
        MediaLibraryClient.clearListener();
    }

    private void refresh() {
        loading = true;
        page = 0;
        MediaLibraryClient.request();
        rebuildWidgets();
    }

    @Override
    protected void rebuildWidgets() {
        clearWidgets();
        int cx = this.width / 2;
        int w = Math.min(310, this.width - 30);
        int left = cx - w / 2;

        Button title = Button.builder(
                Component.literal("§e🖥 服务器媒体库（服务器 cinema/videos 目录）"), btn -> {}
        ).bounds(left, 12, w, 16).build();
        title.active = false;
        addRenderableWidget(title);

        List<String> files = MediaLibraryClient.cached();
        if (loading || files == null) {
            Button loadingBtn = Button.builder(
                    Component.literal("§7正在向服务器请求文件列表…（若一直无响应请检查服务端媒体服务）"),
                    btn -> refresh()
            ).bounds(left, 36, w, 20).build();
            loadingBtn.active = false;
            addRenderableWidget(loadingBtn);
        } else if (files.isEmpty()) {
            Button empty = Button.builder(
                    Component.literal("§e服务器 cinema/videos 目录为空 - 请把视频文件放进服务器目录后再刷新"),
                    btn -> {}
            ).bounds(left, 36, w, 20).build();
            empty.active = false;
            addRenderableWidget(empty);
        } else {
            int maxPage = (files.size() - 1) / ROWS_PER_PAGE;
            page = Math.min(page, maxPage);
            int start = page * ROWS_PER_PAGE;
            int end = Math.min(files.size(), start + ROWS_PER_PAGE);
            int y = 36;
            for (int i = start; i < end; i++) {
                String name = files.get(i);
                String url = MediaLibraryClient.sourceFor(name);
                addRenderableWidget(Button.builder(Component.literal(""),
                        btn -> {
                            ScreenSoundSettingsScreen.playOn(screenId, url);
                            onClose();
                        }
                ).bounds(left, y, 196, 20).build());
                // 文件名超宽时横向滚动显示全部，不省略
                addRenderableWidget(new MarqueeText(left, y, 196, 20,
                        "§a▶ " + name, 0xFFFFFFFF));
                addRenderableWidget(Button.builder(Component.literal("＋队列"),
                        btn -> {
                            com.cinemaforyou.CinemaForYouClient.clientConfig.addToQueue(
                                    screenId.toString(), url);
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                        "§a[CinemaForYou] 已加入队列: " + name
                                                + " §7（播完模式选「自动播放下一个」生效）"));
                            }
                        }
                ).bounds(left + 200, y, 58, 20).build());
                addRenderableWidget(Button.builder(
                        Component.literal(name.equals(pendingDelete)
                                ? "§c⚠确认?" : "✕删除"),
                        btn -> {
                            if (!name.equals(pendingDelete)) {
                                pendingDelete = name;
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                        "§c[CinemaForYou] 将删除服务器上的真实文件（不可恢复），再点一次确认"));
                                rebuildWidgets();
                                return;
                            }
                            pendingDelete = null;
                            com.cinemaforyou.client.network.ClientNetworkHandlers.sendMediaDelete(name);
                        }
                ).bounds(left + 262, y, 48, 20).build());
                // 元数据行：添加时间(文件修改时间) + 添加者
                var metaEntry = MediaLibraryClient.metaOf(name);
                String metaLine;
                if (metaEntry == null) {
                    metaLine = "§7（元数据加载中…）";
                } else {
                    String t = java.time.Instant.ofEpochMilli(metaEntry.mtimeMs())
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    String owner = metaEntry.owner() == null || metaEntry.owner().isEmpty()
                            ? "服务器文件" : metaEntry.owner();
                    metaLine = "§7🕐 " + t + " · 添加者: " + owner;
                }
                addRenderableWidget(new GuiTextLabel(left, y + 21, w, 12, metaLine,
                        GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
                y += 35;
            }

            // 底部固定：刷新(全宽) + 分页条与返回同行
            addRenderableWidget(Button.builder(Component.literal("🔄 刷新列表"),
                    btn -> refresh()
            ).bounds(left, this.height - 56, w, 20).build());

            int py = this.height - 30;
            boolean hasPrev = page > 0;
            Button prev = Button.builder(Component.literal("§l◀"),
                    btn -> { page--; rebuildWidgets(); }
            ).bounds(left, py, 40, 20).build();
            prev.active = hasPrev;
            addRenderableWidget(prev);

            Button pageLabel = Button.builder(
                    Component.literal("§7" + (page + 1) + "/" + (maxPage + 1)), btn -> {}
            ).bounds(left + 45, py, 44, 20).build();
            pageLabel.active = false;
            addRenderableWidget(pageLabel);

            boolean hasNext = page < maxPage;
            Button next = Button.builder(Component.literal("§l▶"),
                    btn -> { page++; rebuildWidgets(); }
            ).bounds(left + 94, py, 40, 20).build();
            next.active = hasNext;
            addRenderableWidget(next);
        }

        addRenderableWidget(Button.builder(
                Component.literal("← 返回上一级"),
                btn -> onClose()
        ).bounds(left + 140, this.height - 30, Math.max(60, w - 140), 20).build());
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
