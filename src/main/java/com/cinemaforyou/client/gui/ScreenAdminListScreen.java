package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.network.ScreenAdminClient;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.network.ScreenAdminActionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 屏幕列表管理：搜索（名称/创建者/描述）+ 分页；显示创建者与创建时间；
 * 改名/描述/删除/全部删除（仅创建者可操作，服务端二次校验）。
 */
public class ScreenAdminListScreen extends ScrollableSettingsScreen {

    private static final int PAGE_SIZE = 5;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private String query = "";
    private int listScroll = 0;
    private static final int ENTRY_H = 86;
    private int page = 0;
    private EditBox searchBox;
    /** 二次确认状态（与本地视频库一致的机制）：待删除屏幕 id / 是否已请求删除全部。 */
    private String pendingDeleteId = null;
    private boolean pendingDeleteAll = false;

    public ScreenAdminListScreen() {
        super(Component.literal("屏幕列表管理"));
    }

    @Override
    protected void init() {
        ScreenAdminClient.setListener(() -> {
            Minecraft.getInstance().execute(this::rebuildWidgets);
        });
        ClientNetworkHandlers.requestScreenMeta();
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
        int w = Math.min(320, this.width - 30);
        int left = cx - w / 2;
        int y = 8;

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                "§e屏幕列表管理（搜索名称/创建者/描述）", GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 15;

        searchBox = new EditBox(this.font, left, ry(y), w - 62, 20,
                Component.literal("搜索"));
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.literal("🔍"),
                btn -> {
                    query = searchBox.getValue() == null ? "" : searchBox.getValue().trim();
                    page = 0;
                    listScroll = 0;
                    rebuildWidgets();
                }
        ).bounds(left + w - 58, ry(y), 58, 20).build());
        y += 24;

        List<CinemaScreen> screens = new ArrayList<>(ClientScreenManager.get().allScreens().values());
        screens.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        List<CinemaScreen> filtered = filter(screens);
        int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(filtered.size(), start + PAGE_SIZE);

        if (filtered.isEmpty()) {
            addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                    "§7没有匹配的屏幕", GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 22;
        } else {
            // 内容区滚动：每页固定 5 条，滚轮只滚动列表，不动页脚/搜索框
            int rowsTop = 47;
            int viewH = Math.max(40, (this.height - 60) - rowsTop);
            int maxList = Math.max(0, PAGE_SIZE * ENTRY_H - viewH);
            if (listScroll > maxList) listScroll = maxList;
            for (int i = start; i < end; i++) {
                CinemaScreen s = filtered.get(i);
                renderEntry(cx, left, w, rowsTop + (i - start) * ENTRY_H - listScroll, s);
            }
        }

        // 底部固定操作区（与播放历史一致：清空类全宽在上，分页+返回在底行）
        int bottom = this.height - 32 + scrollY;
        addRenderableWidget(Button.builder(
                Component.literal(pendingDeleteAll ? "§c⚠确认删除我创建的全部屏幕?" : "🗑 删除我创建的全部屏幕"),
                btn -> {
                    // 全部删除：两次点击确认（与本地视频库删除同一套机制）
                    if (!pendingDeleteAll) {
                        pendingDeleteAll = true;
                        Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                "§c[CinemaForYou] 将删除我创建的全部屏幕（不可恢复），再点一次确认"));
                        rebuildWidgets();
                        return;
                    }
                    pendingDeleteAll = false;
                    ClientNetworkHandlers.sendAdminAction(
                            ScreenAdminActionPayload.ACTION_DELETE_ALL_MINE, UUID.randomUUID(), "");
                    rebuildWidgets();
                }
        ).bounds(left, ry(bottom - 26), w, 20).build());
        final int tPages = totalPages;
        addPager(left, bottom, page, tPages,
                () -> page > 0, () -> page < tPages - 1,
                () -> { page = Math.max(0, page - 1); listScroll = 0; rebuildWidgets(); },
                () -> { page = Math.min(tPages - 1, page + 1); listScroll = 0; rebuildWidgets(); });
        addRenderableWidget(Button.builder(Component.literal("← 返回上一级"),
                btn -> onClose()).bounds(left + 140, bottom, Math.max(60, w - 140), 20).build());

        finishContent(this.height - 6);
    }

    /** 渲染单个屏幕条目（按行裁剪：滚动时逐行进出，不盖住搜索框与页脚）。 */
    private int renderEntry(int cx, int left, int w, int y, CinemaScreen s) {
        String owner = ScreenAdminClient.ownerOf(s.id());
        if (owner == null || owner.isEmpty()) owner = "（未知创建者）";
        String time = FMT.format(Instant.ofEpochMilli(s.createdAt())
                .atZone(ZoneId.systemDefault()));
        boolean mine = isMine(s);
        int clipTop = 43;
        int clipBottom = this.height - 58;
        // 第一行：名称/尺寸/创建者（点击打开控制；悬停才滚动显示全部）
        String meta = "§e" + s.displayName()
                + "§7 [" + s.width() + "x" + s.height() + "]  §f" + owner;
        if (rowVisible(y, y + 20, clipTop, clipBottom)) {
            Button metaBtn = Button.builder(Component.literal(""),
                    btn -> openChild(new ScreenControlScreen(s.id())))
                    .bounds(left, y, w, 20).build();
            addRenderableWidget(metaBtn);
            addRenderableWidget(new MarqueeText(left, y, w, 20, metaBtn, meta));
        }
        // 时间行
        if (rowVisible(y + 22, y + 36, clipTop, clipBottom)) {
            addRenderableWidget(new GuiTextLabel(cx, y + 25, w, 12,
                    "🕐 创建于 " + time + (mine ? "　（你可管理）" : "　（仅创建者可改）"),
                    GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        }
        // 描述行（可点击编辑，仅创建者）
        String desc = ScreenAdminClient.descOf(s.id());
        if (desc.isEmpty()) {
            desc = mine ? "（无描述，点击添加）" : "（无描述）";
        }
        final String d = desc;
        if (rowVisible(y + 42, y + 62, clipTop, clipBottom)) {
            Button descBtn = Button.builder(
                    Component.literal(""),
                    btn -> openDescEditor(s)).bounds(left, y + 42, w, 20).build();
            descBtn.active = mine;
            addRenderableWidget(descBtn);
            // 描述超宽时悬停才滚动显示全部
            addRenderableWidget(new MarqueeText(left, y + 42, w, 20, descBtn,
                    "描述: §f" + d));
        }
        // 操作行
        if (rowVisible(y + 64, y + 84, clipTop, clipBottom)) {
            if (mine) {
                int half = w / 2 - 3;
                addRenderableWidget(Button.builder(Component.literal("✎ 改名"),
                        btn -> openChild(new InputValueScreen(
                                "修改屏幕名称（1-32字符，无空格）", s.customId() == null ? "" : s.customId(), 32,
                                v -> ClientNetworkHandlers.sendAdminAction(
                                        ScreenAdminActionPayload.ACTION_RENAME, s.id(), v)))
                ).bounds(left, y + 64, half, 20).build());
                addRenderableWidget(Button.builder(
                        Component.literal(s.id().toString().equals(pendingDeleteId)
                                ? "§c⚠确认删除?" : "🗑 删除"),
                        btn -> {
                            // 单屏删除：两次点击确认（与本地视频库删除同一套机制）
                            String id = s.id().toString();
                            if (!id.equals(pendingDeleteId)) {
                                pendingDeleteId = id;
                                Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                                        "§c[CinemaForYou] 将删除屏幕「" + s.displayName()
                                                + "」（不可恢复），再点一次确认"));
                                rebuildWidgets();
                                return;
                            }
                            pendingDeleteId = null;
                            ClientNetworkHandlers.sendAdminAction(
                                    ScreenAdminActionPayload.ACTION_DELETE, s.id(), "");
                            rebuildWidgets();
                        })
                        .bounds(left + half + 6, y + 64, half, 20).build());
            } else {
                addRenderableWidget(new GuiTextLabel(cx, y + 67, w, 12,
                        "§7仅创建者可改名/删除/改描述", GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            }
        }
        return y + 86;
    }

    /** 行是否位于可显示区：整行在边界内才绘制（不会画出残缺行盖住搜索框）。 */
    private boolean rowVisible(int top, int bottom, int clipTop, int clipBottom) {
        if (top < clipTop) return false;
        if (bottom > clipBottom) return false;
        return true;
    }

    private void openDescEditor(CinemaScreen s) {
        openChild(new InputValueScreen(
                "修改描述（可留空，≤200 字符）", ScreenAdminClient.descOf(s.id()), 200,
                v -> ClientNetworkHandlers.sendAdminAction(
                        ScreenAdminActionPayload.ACTION_SET_DESC, s.id(), v)));
    }

    private boolean isMine(CinemaScreen s) {
        try {
            net.minecraft.client.player.LocalPlayer p = Minecraft.getInstance().player;
            return p != null && s.ownerId().equals(p.getUUID().toString());
        } catch (Throwable t) {
            return false;
        }
    }

    private List<CinemaScreen> filter(List<CinemaScreen> screens) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return screens;
        List<CinemaScreen> out = new ArrayList<>();
        for (CinemaScreen s : screens) {
            String owner = ScreenAdminClient.ownerOf(s.id());
            String desc = ScreenAdminClient.descOf(s.id());
            if (s.displayName().toLowerCase().contains(q)
                    || (s.customId() != null && s.customId().toLowerCase().contains(q))
                    || (owner != null && owner.toLowerCase().contains(q))
                    || desc.toLowerCase().contains(q)) {
                out.add(s);
            }
        }
        return out;
    }

    /** 滚轮/方向键只滚动列表内容区（页脚与搜索框固定）。 */
    private boolean scrollListBy(int delta) {
        int rowsTop = 47;
        int viewH = Math.max(40, (this.height - 60) - rowsTop);
        int maxList = Math.max(0, PAGE_SIZE * ENTRY_H - viewH);
        int target = Math.max(0, Math.min(maxList, listScroll + delta));
        if (target != listScroll) {
            listScroll = target;
            rebuildWidgets();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        return scrollListBy(verticalAmount > 0 ? -22 : 22);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == 264) return scrollListBy(22);
        if (event.key() == 265) return scrollListBy(-22);
        return super.keyPressed(event);
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
