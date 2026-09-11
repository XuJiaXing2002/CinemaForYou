package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.network.ClientNetworkHandlers;
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
 * 点 ▶ 播放；＋队列 加入播放队列；支持刷新。
 *
 * <ul>
 *   <li>无参构造：总设置入口——向所有屏幕的 owner 发送播放申请（首次点击提示确认，
 *       再点一次才下发）；「＋队列」加入全局播放队列（不参与自动连播）；</li>
 *   <li>{@link #ServerMediaScreen(UUID)}：屏幕控制页入口——只作用于该屏。</li>
 * </ul>
 */
public class ServerMediaScreen extends Screen {
    @Override
    public void onClose() {
        if (!GuiNav.back(this)) {
            super.onClose();
        }
    }


    private static final int ROWS_PER_PAGE = 4;

    /** 目标屏幕；null = 总设置入口（播放到所有屏幕）。 */
    private final UUID screenId;
    private int page = 0;
    private boolean loading = true;
    private String pendingDelete = null;
    /** 全局播放入口的二次确认：待确认播放的 URL（首次点击只提示，再点一次真正播放）。 */
    private String pendingPlayUrl = null;

    /** 总设置入口：播放/入队作用于所有屏幕。 */
    public ServerMediaScreen() {
        this(null);
    }

    /** @param screenId 屏幕控制页入口（只作用于该屏）；null = 总设置入口（所有屏幕）。 */
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
                Component.literal(screenId == null
                        ? "§e🖥 服务器媒体库 → 向所有屏幕发送播放申请"
                        : "§e🖥 服务器媒体库（服务器 cinema/videos 目录）"), btn -> {}
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
                Button playBtn = Button.builder(Component.literal(""),
                        btn -> {
                            if (screenId == null) {
                                // 总设置入口：向所有屏幕发播放申请（两次点击确认；被拒时留在本页）
                                if (!url.equals(pendingPlayUrl)) {
                                    pendingPlayUrl = url;
                                    chat("§e[CinemaForYou] 将向所有屏幕发送播放申请，再点一次确认");
                                    rebuildWidgets();
                                    return;
                                }
                                if (ScreenSoundSettingsScreen.playOnAll(url)) {
                                    pendingPlayUrl = null;
                                    onClose();
                                }
                            } else if (ScreenSoundSettingsScreen.playOn(screenId, url)) {
                                onClose();
                            }
                        }
                ).bounds(left, y, 196, 20).build();
                addRenderableWidget(playBtn);
                // 文件名超宽时悬停才滚动显示全部；待确认时给出明确提示
                addRenderableWidget(new MarqueeText(left, y, 196, 20, playBtn,
                        url.equals(pendingPlayUrl)
                                ? "§c⚠ 再点一次: 向所有屏幕发送播放申请 ▶ " + name
                                : "§a▶ " + name));
                addRenderableWidget(Button.builder(Component.literal("＋队列"),
                        btn -> {
                            // 总设置入口加入全局播放队列（不自动连播）；控制页入口只加入该屏（服务端记录玩家名/时间）
                            if (screenId == null) {
                                ClientNetworkHandlers.sendGlobalQueueAdd(url);
                            } else {
                                ClientNetworkHandlers.sendQueueAdd(screenId, url);
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

    private static void chat(String msg) {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.sendSystemMessage(Component.literal(msg));
        }
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
