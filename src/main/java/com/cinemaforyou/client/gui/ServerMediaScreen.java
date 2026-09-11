package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.network.MediaLibraryClient;
import com.cinemaforyou.client.network.MediaUploader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务器媒体库：列出 服务器目录/cinema/videos/ 下的媒体文件。
 *
 * <p>两种视图（同一界面类）：
 * <ul>
 *   <li><b>主页（汇总）</b>：按"添加者"分组，一行一个玩家「玩家名  视频 N 个」，
 *       点击进入该玩家的明细页；直接放进服务器文件夹（无添加者记录）的文件归入
 *       「服务器文件」分组并<b>置顶</b>。主页顶部提供「浏览本地文件夹并上传」
 *       （仅 OP≥2，分块上传见 {@link MediaUploader}），含进度与取消。</li>
 *   <li><b>明细页</b>：沿用原有条目渲染与全部按钮（▶播放 / ＋队列 / ✕删除，逻辑不变）；
 *       条目标题带创建时间并沿用 {@link MarqueeText} 滚动显示；标题不再带添加者名字
 *       （主页已能确定是谁的）。</li>
 * </ul>
 *
 * <p>顶部与分组标题统一使用黄色文字标题（{@link GuiTextLabel} / §e 文本），
 * 不使用灰色不可点击按钮框。
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

    /** 「服务器文件」分组（无添加者记录的文件）的分组键。 */
    private static final String SERVER_FILES_OWNER = "";

    /** 创建/上传时间显示格式（与播放历史/队列明细一致）。 */
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int HOME_ROW_H = 24;
    private static final int DETAIL_ROW_H = 22;

    /** 目标屏幕；null = 总设置入口（播放到所有屏幕）。 */
    private final UUID screenId;
    /** 明细页的添加者；null = 主页汇总视图（"" = 服务器文件分组）。 */
    private final String detailOwner;
    private final boolean detailView;

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
        this(screenId, null);
    }

    /** @param detailOwner 添加者分组（"" = 服务器文件）；null = 主页汇总视图。 */
    private ServerMediaScreen(UUID screenId, String detailOwner) {
        super(Component.literal("服务器媒体库"));
        this.screenId = screenId;
        this.detailOwner = detailOwner;
        this.detailView = detailOwner != null;
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
        // 上传进度变化 → 刷新界面（上传与界面是否打开无关，聊天栏另有里程碑提示）
        MediaUploader.setListener(() -> Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().gui.screen() == this) {
                rebuildWidgets();
            }
        }));
        refresh();
    }

    @Override
    public void removed() {
        super.removed();
        MediaLibraryClient.clearListener();
        MediaUploader.clearListener();
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

        List<String> files = MediaLibraryClient.cached();
        // 顶部标题：统一黄色文字标题（无按钮框）
        String titleText = detailView
                ? titleForDetail(files)
                : (screenId == null
                        ? "§e🖥 服务器媒体库 → 向所有屏幕发送播放申请"
                        : "§e🖥 服务器媒体库（服务器 cinema/videos 目录）");
        // 标题贴屏幕顶部（原 y=10 → 2），下方头部/列表随之上移，行距不变
        addRenderableWidget(new GuiTextLabel(cx, 2, w, 12, titleText,
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));

        int listTop;
        if (detailView) {
            listTop = 22;
        } else {
            listTop = renderHomeHeader(left, w);
        }

        if (loading || files == null) {
            addRenderableWidget(new GuiTextLabel(left, listTop + 4, w, 12,
                    "§7正在向服务器请求文件列表…（若一直无响应请检查服务端媒体服务）",
                    GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
            renderBottom(left, w, 0);
            return;
        }

        int maxPage = detailView
                ? renderDetailList(left, w, files, listTop)
                : renderHomeList(left, w, files, listTop);
        renderBottom(left, w, maxPage);
    }

    // ───────────── 主页（按添加者分组） ─────────────

    /** 主页头部（上传按钮 + 进度/提示），返回列表起始 y。 */
    private int renderHomeHeader(int left, int w) {
        // 整体上移贴顶（原 y=26 → 18），头部到列表的行距保持原样
        int y = 18;
        Button uploadBtn = Button.builder(
                Component.literal("📤 浏览本地文件夹并上传（仅管理员 OP≥2）"),
                btn -> openFileChooser()
        ).bounds(left, y, w, 20).build();
        // OP 置灰（与服务端校验一致：非 OP 服务端也会拒绝）
        uploadBtn.active = hasOpPermission();
        addRenderableWidget(uploadBtn);
        y += 24;

        if (MediaUploader.isUploading()) {
            addRenderableWidget(new GuiTextLabel(left, y + 4, Math.max(40, w - 70), 12,
                    MediaUploader.statusText(), GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
            addRenderableWidget(Button.builder(Component.literal("✕取消上传"),
                    btn -> MediaUploader.cancel()
            ).bounds(left + w - 66, y, 66, 20).build());
        } else if (!hasOpPermission()) {
            addRenderableWidget(new GuiTextLabel(left, y + 4, w, 12,
                    "§7仅管理员（OP≥2）可上传；上传文件将保存进服务器 media 目录",
                    GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
        } else {
            addRenderableWidget(new GuiTextLabel(left, y + 4, w, 12,
                    "§7仅限视频/音频文件；重名自动加序号；上传可随时取消",
                    GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
        }
        return y + 20;
    }

    /** 主页列表：一行一个添加者分组（服务器文件置顶）。返回最大页数。 */
    private int renderHomeList(int left, int w, List<String> files, int listTop) {
        if (files.isEmpty()) {
            addRenderableWidget(new GuiTextLabel(left, listTop + 4, w, 12,
                    "§e服务器 cinema/videos 目录为空 - 可点上方「浏览本地文件夹并上传」，"
                            + "或把视频文件放进服务器目录后刷新",
                    GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
            return 0;
        }
        if (metaStillLoading(files)) {
            addRenderableWidget(new GuiTextLabel(left, listTop + 4, w, 12,
                    "§7正在加载文件元数据（添加者/创建时间）…",
                    GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
            return 0;
        }
        List<OwnerGroup> groups = buildGroups(files);
        int maxRows = Math.max(1, (this.height - 60 - listTop) / HOME_ROW_H);
        int maxPage = Math.max(0, (groups.size() - 1) / maxRows);
        page = Math.min(page, maxPage);
        int start = page * maxRows;
        int end = Math.min(groups.size(), start + maxRows);
        int y = listTop;
        for (int i = start; i < end; i++) {
            OwnerGroup g = groups.get(i);
            String label = g.owner().isEmpty()
                    ? "📁 服务器文件  视频 " + g.files().size() + " 个"
                    : "👤 " + g.owner() + "  视频 " + g.files().size() + " 个";
            // 整行可点击进入明细页；文字统一黄色（§e），长名悬停滚动
            Button rowBtn = Button.builder(Component.literal(""),
                    btn -> GuiNav.open(this, new ServerMediaScreen(screenId, g.owner()))
            ).bounds(left, y, w, 20).build();
            addRenderableWidget(rowBtn);
            addRenderableWidget(new MarqueeText(left, y, w, 20, rowBtn, "§e" + label));
            y += HOME_ROW_H;
        }
        return maxPage;
    }

    /** 主页分组：服务器文件（无添加者）置顶，其余玩家按名称排序。 */
    private List<OwnerGroup> buildGroups(List<String> files) {
        Map<String, List<String>> byOwner = new LinkedHashMap<>();
        for (String name : files) {
            var meta = MediaLibraryClient.metaOf(name);
            String owner = (meta == null || meta.owner() == null) ? "" : meta.owner().trim();
            byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(name);
        }
        List<OwnerGroup> groups = new ArrayList<>();
        List<String> serverFiles = byOwner.remove(SERVER_FILES_OWNER);
        if (serverFiles != null && !serverFiles.isEmpty()) {
            groups.add(new OwnerGroup(SERVER_FILES_OWNER, serverFiles));
        }
        List<String> owners = new ArrayList<>(byOwner.keySet());
        owners.sort(String::compareToIgnoreCase);
        for (String o : owners) {
            groups.add(new OwnerGroup(o, byOwner.get(o)));
        }
        return groups;
    }

    /** 元数据（添加者/时间）尚未到达时暂不分组，避免整屏先闪成"服务器文件"。 */
    private boolean metaStillLoading(List<String> files) {
        for (String name : files) {
            if (MediaLibraryClient.metaOf(name) != null) return false;
        }
        return true;
    }

    // ───────────── 明细页（沿用原有条目渲染与按钮） ─────────────

    private String titleForDetail(List<String> files) {
        int count = 0;
        if (files != null) {
            for (String name : files) {
                var meta = MediaLibraryClient.metaOf(name);
                String owner = (meta == null || meta.owner() == null) ? "" : meta.owner().trim();
                if (owner.equals(detailOwner)) count++;
            }
        }
        String who = detailOwner.isEmpty() ? "服务器文件" : detailOwner;
        return "§e🖥 服务器媒体库 · " + who + "（视频 " + count + " 个）";
    }

    /** 明细页列表：该添加者的文件，条目渲染/按钮逻辑与原服务器媒体库一致。返回最大页数。 */
    private int renderDetailList(int left, int w, List<String> files, int listTop) {
        if (!files.isEmpty() && metaStillLoading(files)) {
            addRenderableWidget(new GuiTextLabel(left, listTop + 4, w, 12,
                    "§7正在加载文件元数据（添加者/创建时间）…",
                    GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
            return 0;
        }
        List<String> mine = new ArrayList<>();
        for (String name : files) {
            var meta = MediaLibraryClient.metaOf(name);
            String owner = (meta == null || meta.owner() == null) ? "" : meta.owner().trim();
            if (owner.equals(detailOwner)) mine.add(name);
        }
        if (mine.isEmpty()) {
            addRenderableWidget(new GuiTextLabel(left, listTop + 4, w, 12,
                    "§7该分组已没有文件 - 点下方「刷新列表」重新拉取",
                    GuiTextLabel.Align.LEFT, GuiTextLabel.YELLOW));
            return 0;
        }
        int maxRows = Math.max(1, (this.height - 60 - listTop) / DETAIL_ROW_H);
        int maxPage = Math.max(0, (mine.size() - 1) / maxRows);
        page = Math.min(page, maxPage);
        int start = page * maxRows;
        int end = Math.min(mine.size(), start + maxRows);
        int playW = w - 112;
        int y = listTop;
        for (int i = start; i < end; i++) {
            String name = mine.get(i);
            String url = MediaLibraryClient.sourceFor(name);
            // 创建/上传时间并入条目标题（滚动显示），标题不再带添加者名字
            var meta = MediaLibraryClient.metaOf(name);
            String timeText = (meta != null && meta.mtimeMs() > 0)
                    ? TIME_FMT.format(Instant.ofEpochMilli(meta.mtimeMs())
                            .atZone(ZoneId.systemDefault()))
                    : null;
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
            ).bounds(left, y, playW, 20).build();
            addRenderableWidget(playBtn);
            // 标题：文件名 + 时间；超宽时悬停滚动显示全部（与其他长标题一致）；
            // 按用户要求去掉时间文字前的「创建」二字，直接显示时间
            String shown = url.equals(pendingPlayUrl)
                    ? "§c⚠ 再点一次: 向所有屏幕发送播放申请 ▶ " + name
                    : "§a▶ " + name + (timeText != null
                            ? "  §7" + timeText : "  §7(创建时间加载中…)");
            addRenderableWidget(new MarqueeText(left, y, playW, 20, playBtn, shown));
            addRenderableWidget(Button.builder(Component.literal("＋队列"),
                    btn -> {
                        // 总设置入口加入全局播放队列（不自动连播）；控制页入口只加入该屏
                        if (screenId == null) {
                            ClientNetworkHandlers.sendGlobalQueueAdd(url);
                        } else {
                            ClientNetworkHandlers.sendQueueAdd(screenId, url);
                        }
                    }
            ).bounds(left + playW + 4, y, 54, 20).build());
            addRenderableWidget(Button.builder(
                    Component.literal(name.equals(pendingDelete) ? "§c⚠确认?" : "✕删除"),
                    btn -> {
                        if (!name.equals(pendingDelete)) {
                            pendingDelete = name;
                            chat("§c[CinemaForYou] 将删除服务器上的真实文件（不可恢复），再点一次确认");
                            rebuildWidgets();
                            return;
                        }
                        pendingDelete = null;
                        ClientNetworkHandlers.sendMediaDelete(name);
                    }
            ).bounds(left + playW + 60, y, 48, 20).build());
            y += DETAIL_ROW_H;
        }
        return maxPage;
    }

    // ───────────── 底部：刷新 / 分页 / 返回 ─────────────

    private void renderBottom(int left, int w, int maxPage) {
        addRenderableWidget(Button.builder(Component.literal("🔄 刷新列表"),
                btn -> refresh()
        ).bounds(left, this.height - 56, w, 20).build());
        addPager(left, w, maxPage);
        addRenderableWidget(Button.builder(
                Component.literal("← 返回上一级"),
                btn -> onClose()
        ).bounds(left + 140, this.height - 30, Math.max(60, w - 140), 20).build());
    }

    /** 统一分页条 [◀][p/t][▶]（无分页时显示 1/1 置灰）。 */
    private void addPager(int left, int w, int maxPage) {
        int py = this.height - 30;
        Button prev = Button.builder(Component.literal("§l◀"),
                btn -> { page--; rebuildWidgets(); }
        ).bounds(left, py, 40, 20).build();
        prev.active = page > 0;
        addRenderableWidget(prev);

        Button pageLabel = Button.builder(
                Component.literal("§7" + (page + 1) + "/" + (maxPage + 1)), btn -> {}
        ).bounds(left + 45, py, 44, 20).build();
        pageLabel.active = false;
        addRenderableWidget(pageLabel);

        Button next = Button.builder(Component.literal("§l▶"),
                btn -> { page++; rebuildWidgets(); }
        ).bounds(left + 94, py, 40, 20).build();
        next.active = page < maxPage;
        addRenderableWidget(next);
    }

    // ───────────── 上传入口（系统文件选择器） ─────────────

    /**
     * 打开系统文件选择窗口挑选要上传的本地视频（与文件选择屏同一套 Swing 实现：
     * 置顶空父窗口防止被全屏游戏遮挡），选中后回到主线程启动分块上传。
     */
    private void openFileChooser() {
        if (!hasOpPermission()) {
            chat("§c[CinemaForYou] 只有管理员（OP≥2）可以上传视频到服务器媒体库");
            return;
        }
        if (MediaUploader.isUploading()) {
            chat("§e[CinemaForYou] 已有上传任务进行中，请等待完成或先取消");
            return;
        }
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            chat("§c[CinemaForYou] Java 运行于 headless 模式，无法弹出文件窗口。"
                    + "请在启动器 JVM 参数中加入 -Djava.awt.headless=false 后重启");
            return;
        }
        chat("§7[CinemaForYou] 正在打开文件选择窗口（首次打开需数秒，请稍候）…");

        java.io.File startDir = null;
        var cfg = CinemaForYouClient.clientConfig;
        if (cfg != null) {
            java.nio.file.Path dir = cfg.resolveVideosDir();
            java.io.File f = dir.toFile();
            startDir = f.exists() ? f : null;
        }
        final java.io.File finalStart = startDir;

        java.awt.EventQueue.invokeLater(() -> {
            try {
                javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(finalStart);
                chooser.setDialogTitle("选择要上传到服务器的视频文件");
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
                if (selected == null) return; // 用户取消选择
                if (!MediaUploader.isSupportedVideoName(selected.getName())) {
                    chat("§c[CinemaForYou] 不是支持的视频格式，已拒绝: " + selected.getName());
                    return;
                }
                Minecraft.getInstance().execute(() -> MediaUploader.start(selected));
            } catch (Throwable t) {
                chat("§c[CinemaForYou] 打开文件选择器失败: " + t);
            }
        });
    }

    /** 当前玩家是否 OP（权限等级 ≥2），与服务端上传校验同一套判定。 */
    private static boolean hasOpPermission() {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.permissions().hasPermission(
                new net.minecraft.server.permissions.Permission.HasCommandLevel(
                        net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS));
    }

    private static void chat(String msg) {
        net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.sendSystemMessage(Component.literal(msg));
        }
    }

    /** 主页一个添加者分组（owner 为空 = 服务器文件分组）。 */
    private record OwnerGroup(String owner, List<String> files) {}

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.fill(0, 0, this.width, this.height, 0x90101014);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
