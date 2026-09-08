package com.cinemaforyou.client.gui;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.ClientScreenManager;
import com.cinemaforyou.client.config.ClientConfig;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.network.ScreenAdminClient;
import com.cinemaforyou.data.CinemaScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 目标屏幕搜索选择器：按名称/创建者搜索，点选后作为"目标屏幕"
 * （供总设置里的队列/播完行为等使用），并返回上级界面。
 */
public class TargetSearchScreen extends ScrollableSettingsScreen {

    private static final int PAGE_SIZE = 7;

    private String query = "";
    private int page = 0;
    private EditBox searchBox;

    public TargetSearchScreen() {
        super(Component.literal("选择目标屏幕"));
    }

    @Override
    protected void init() {
        ScreenAdminClient.setListener(() ->
                Minecraft.getInstance().execute(this::rebuildWidgets));
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
                "§e选择目标屏幕（搜索名称或创建者）", GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 15;

        searchBox = new EditBox(this.font, left, ry(y), w - 62, 20,
                Component.literal("搜索名称/创建者"));
        searchBox.setValue(query);
        addRenderableWidget(searchBox);
        addRenderableWidget(Button.builder(Component.literal("🔍"),
                btn -> {
                    query = searchBox.getValue() == null ? "" : searchBox.getValue().trim();
                    page = 0;
                    rebuildWidgets();
                }
        ).bounds(left + w - 58, ry(y), 58, 20).build());
        y += 24;

        List<CinemaScreen> all = new ArrayList<>(ClientScreenManager.get().allScreens().values());
        all.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        List<CinemaScreen> filtered = filter(all);
        int totalPages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(filtered.size(), start + PAGE_SIZE);

        if (filtered.isEmpty()) {
            addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                    "§7没有匹配的屏幕（先创建屏幕）", GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 22;
        } else {
            for (int i = start; i < end; i++) {
                CinemaScreen s = filtered.get(i);
                String owner = ScreenAdminClient.ownerOf(s.id());
                if (owner == null || owner.isEmpty()) owner = "？";
                String label = UiText.fit("§e" + s.displayName()
                        + "§7 [" + s.width() + "x" + s.height() + "]  §f" + owner, w - 10);
                addRenderableWidget(Button.builder(Component.literal(label),
                        btn -> pick(s))
                        .bounds(left, ry(y), w, 20).build());
                y += 22;
            }
            y += 2;
        }

        // 底部固定操作区（与其它列表页一致）
        int bottom = this.height - 32 + scrollY;
        final int tPages = totalPages;
        addPager(left, bottom, page, tPages,
                () -> page > 0, () -> page < tPages - 1,
                () -> { page = Math.max(0, page - 1); rebuildWidgets(); },
                () -> { page = Math.min(tPages - 1, page + 1); rebuildWidgets(); });
        addRenderableWidget(Button.builder(Component.literal("← 返回上一级"),
                btn -> onClose()).bounds(left + 140, bottom, Math.max(60, w - 140), 20).build());

        finishContent(Math.max(y, bottom + 24));
    }

    private void pick(CinemaScreen s) {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null) {
            cfg.lastTargetScreenId = s.id().toString();
            cfg.save();
        }
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] 目标屏幕: §e" + s.displayName()));
        }
        // 让上级设置页刷新后再返回
        CinemaSettingsScreen.refreshOnReturn();
        GuiNav.back(this);
    }

    private List<CinemaScreen> filter(List<CinemaScreen> screens) {
        String q = query == null ? "" : query.trim().toLowerCase();
        if (q.isEmpty()) return screens;
        List<CinemaScreen> out = new ArrayList<>();
        for (CinemaScreen s : screens) {
            String owner = ScreenAdminClient.ownerOf(s.id());
            if (s.displayName().toLowerCase().contains(q)
                    || (s.customId() != null && s.customId().toLowerCase().contains(q))
                    || (owner != null && owner.toLowerCase().contains(q))) {
                out.add(s);
            }
        }
        return out;
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
