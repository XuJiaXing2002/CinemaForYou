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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 屏幕搜索选择器（两级：玩家分组主页 → 某玩家的屏幕列表）：
 * 主页一行一个玩家（玩家名 + 屏幕 N 个），点玩家行进入该玩家的屏幕列表，
 * 点某屏完成选择（写入屏幕控制页的 {@link ClientConfig#lastControlScreenId} 并返回上级界面，
 * 控制页据此切换当前屏幕）。
 *
 * <p>列出<b>所有玩家</b>的屏幕（{@link ClientScreenManager#allScreens()} = 服务端全量同步，
 * 不按 owner 过滤、也不过滤自己的屏幕）：主页玩家行显示「玩家名 + 屏幕 N 个」，
 * 明细页每行显示 屏幕名 + 归属玩家名 + 坐标 + 创建时间；
 * 两层各有一个搜索框（名称/自定义名/创建者），分页与返回机制同其它列表页。
 *
 * <p>选择逻辑、搜索过滤（名称/自定义名/创建者）与返回刷新机制全部保持现状，仅改层级与展示。
 */
public class TargetSearchScreen extends ScrollableSettingsScreen {

    private static final int PAGE_SIZE = 7;
    /** 屏幕创建时间显示格式（与播放历史/队列明细一致）。 */
    private static final DateTimeFormatter CREATE_TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 玩家分组键（屏幕 ownerId）；null = 主页（按玩家分组）。"" = 无 owner 信息的"未知玩家"分组。 */
    private final String ownerKey;
    /** 分组玩家显示名（明细页标题用；主页为 null）。 */
    private final String ownerLabel;

    private String query = "";
    private int page = 0;
    private EditBox searchBox;

    /** 主页：按玩家（屏幕所属 owner）分组列出。 */
    public TargetSearchScreen() {
        this(null, null);
    }

    /**
     * 某玩家的屏幕列表页：只列出归属该玩家的屏幕，点某屏完成选择（与主页同一套选择逻辑）。
     *
     * @param ownerKey   该玩家的 ownerId（"" = "未知玩家"分组）
     * @param ownerLabel 该玩家的显示名（未知为"未知玩家"）
     */
    public TargetSearchScreen(String ownerKey, String ownerLabel) {
        super(Component.literal("选择屏幕"));
        this.ownerKey = ownerKey;
        this.ownerLabel = ownerLabel;
    }

    /** 是否玩家分组主页（ownerKey 为 null）。 */
    private boolean isHome() {
        return ownerKey == null;
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
        // 内容起始 y=2：标题贴屏幕顶部，去掉原来的顶部留白（行距保持不变）
        int y = 2;

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                isHome()
                        ? "§e选择屏幕（所有玩家，按玩家分组，搜索名称或创建者）"
                        : "§e选择屏幕：" + ownerLabel + "（搜索名称或创建者）",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
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

        // 主页：按 owner 分组，一行一个玩家（玩家名 + 屏幕 N 个）；点玩家行 → 该玩家的屏幕列表
        // 明细页：该玩家的屏幕列表，点某屏完成选择（选择逻辑与原来完全一致）
        List<PlayerGroup> groups = isHome() ? groupByOwner(filtered) : null;
        List<CinemaScreen> shown = isHome() ? null : screensOfOwner(filtered);
        int total = isHome() ? groups.size() : shown.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(total, start + PAGE_SIZE);

        if (total == 0) {
            addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                    "§7没有匹配的屏幕（先创建屏幕）", GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 22;
        } else if (isHome()) {
            for (int i = start; i < end; i++) {
                PlayerGroup g = groups.get(i);
                String label = UiText.fit("§b" + g.label() + "  §7屏幕 " + g.screens().size() + " 个", w - 10);
                addRenderableWidget(Button.builder(Component.literal(label),
                        btn -> openChild(new TargetSearchScreen(g.key(), g.label())))
                        .bounds(left, ry(y), w, 20).build());
                y += 22;
            }
            y += 2;
        } else {
            for (int i = start; i < end; i++) {
                CinemaScreen s = shown.get(i);
                // 明细行：屏幕名 + 归属玩家名 + 坐标 + 创建时间（归属一眼可见，便于选他人屏幕）；
                // 信息全部放进按钮（MarqueeText 只做控制层，超宽时悬停自动滚动显示全部）
                String time = CREATE_TIME_FMT.format(Instant.ofEpochMilli(s.createdAt())
                        .atZone(ZoneId.systemDefault()));
                String label = "§e" + s.displayName()
                        + "  §b" + ownerLabel
                        + "§7 @ " + s.center().toShortString() + "  §7" + time;
                Button rowBtn = Button.builder(Component.literal(""), btn -> pick(s))
                        .bounds(left, ry(y), w, 20).build();
                addRenderableWidget(rowBtn);
                addRenderableWidget(new MarqueeText(left, ry(y), w, 20, rowBtn, label));
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

    /** 主页玩家分组：key = ownerId（"" = 未知玩家），label = 显示名，screens = 该玩家屏幕。 */
    private record PlayerGroup(String key, String label, List<CinemaScreen> screens) {}

    /** 按 owner 分组（搜索过滤后的屏幕），组按玩家名排序。 */
    private static List<PlayerGroup> groupByOwner(List<CinemaScreen> screens) {
        Map<String, List<CinemaScreen>> map = new LinkedHashMap<>();
        for (CinemaScreen s : screens) {
            map.computeIfAbsent(ownerKeyOf(s), k -> new ArrayList<>()).add(s);
        }
        List<PlayerGroup> out = new ArrayList<>();
        for (Map.Entry<String, List<CinemaScreen>> e : map.entrySet()) {
            out.add(new PlayerGroup(e.getKey(), ownerLabelOf(e.getValue()), e.getValue()));
        }
        out.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        return out;
    }

    /** 明细页：只保留归属该 owner 的屏幕（无 owner 信息的归入 ""="未知玩家"）。 */
    private List<CinemaScreen> screensOfOwner(List<CinemaScreen> screens) {
        List<CinemaScreen> out = new ArrayList<>();
        for (CinemaScreen s : screens) {
            if (ownerKeyOf(s).equals(ownerKey)) out.add(s);
        }
        return out;
    }

    /** 分组键：屏幕 ownerId（缺失/为空归入 ""="未知玩家"）。 */
    private static String ownerKeyOf(CinemaScreen s) {
        return s.ownerId() == null ? "" : s.ownerId();
    }

    /** 分组显示名：取组内任一屏幕的创建者元数据名；没有则"未知玩家"。 */
    private static String ownerLabelOf(List<CinemaScreen> screens) {
        for (CinemaScreen s : screens) {
            String owner = ScreenAdminClient.ownerOf(s.id());
            if (owner != null && !owner.isEmpty()) return owner;
        }
        return "未知玩家";
    }

    private void pick(CinemaScreen s) {
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg != null) {
            cfg.lastControlScreenId = s.id().toString();
            cfg.save();
        }
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] 已选择屏幕: §e" + s.displayName()));
        }
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
