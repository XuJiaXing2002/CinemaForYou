package com.cinemaforyou.client.gui;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 屏幕选择列表（Shift+按键 打开）：两级——玩家分组主页（玩家名 + 屏幕 N 个）→ 某玩家的屏幕列表；
 * 点某屏打开该屏幕的控制/设置，与玩家位置/朝向/距离无关。
 *
 * <p>两层都有搜索框（与 {@link TargetSearchScreen} 同款：标题下方、右侧 🔍），
 * 过滤规则同为 屏幕名/自定义名/创建者；分页与返回机制同其它列表页。
 *
 * <p>选中后的既有回调/语义不变：点屏幕行 = {@code openChild(new ScreenControlScreen(screen.id()))}
 * （原来的“点某一项打开该屏控制/设置”完全一致）。
 */
public class ScreenPickerScreen extends ScrollableSettingsScreen {

    private static final int PAGE_SIZE = 7;

    /** 玩家分组键（屏幕 ownerId）；null = 主页（按玩家分组）。"" = 无 owner 信息的"未知玩家"分组。 */
    private final String ownerKey;
    /** 分组玩家显示名（明细页标题用；主页为 null）。 */
    private final String ownerLabel;

    private String query = "";
    private int page = 0;
    private EditBox searchBox;

    /** 主页：按玩家（屏幕所属 owner）分组列出。 */
    public ScreenPickerScreen() {
        this(null, null);
    }

    /**
     * 某玩家的屏幕列表页：只列出归属该玩家的屏幕，点某屏打开其控制页（回调与原来一致）。
     *
     * @param ownerKey   该玩家的 ownerId（"" = "未知玩家"分组）
     * @param ownerLabel 该玩家的显示名（未知为"未知玩家"）
     */
    public ScreenPickerScreen(String ownerKey, String ownerLabel) {
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
        // 玩家显示名来自屏幕元数据（ownerName）；到达后重建界面
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
        // 内容起始 y=2：标题贴屏幕顶部（与 TargetSearchScreen 一致）
        int y = 2;

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                isHome()
                        ? "§e选择屏幕（按玩家分组，搜索名称或创建者）"
                        : "§e选择屏幕：" + ownerLabel + "（搜索名称或创建者）",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 15;

        // 搜索框：两层各一个（与 TargetSearchScreen 同款同位置）
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

        // 主页：一行一个玩家（玩家名 + 屏幕 N 个）→ 该玩家屏幕列表；
        // 明细页：该玩家的屏幕列表，点某屏打开其控制页（回调语义与原来一致）
        List<PlayerGroup> groups = isHome() ? groupByOwner(filtered) : null;
        List<CinemaScreen> shown = isHome() ? null : screensOfOwner(filtered);
        int total = isHome() ? groups.size() : shown.size();
        int totalPages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        if (page >= totalPages) page = totalPages - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(total, start + PAGE_SIZE);

        if (total == 0) {
            addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                    "§7当前没有可用的屏幕（先在游戏里创建屏幕）",
                    GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
            y += 22;
        } else if (isHome()) {
            for (int i = start; i < end; i++) {
                PlayerGroup g = groups.get(i);
                String label = UiText.fit("§b" + g.label() + "  §7屏幕 " + g.screens().size() + " 个", w - 10);
                addRenderableWidget(Button.builder(Component.literal(label),
                        btn -> openChild(new ScreenPickerScreen(g.key(), g.label())))
                        .bounds(left, ry(y), w, 20).build());
                y += 22;
            }
            y += 2;
        } else {
            for (int i = start; i < end; i++) {
                CinemaScreen screen = shown.get(i);
                // 条目内容与原来一致：屏幕名 + 尺寸 + 当前片源；点击打开该屏控制（回调未改）
                String name = "§e" + screen.displayName()
                        + "§7 [" + screen.width() + "x" + screen.height() + "]";
                String src = screen.sourceUrl() == null || screen.sourceUrl().isEmpty()
                        ? "（空）"
                        : ClientConfig.displayNameFor(screen.sourceUrl());
                String fullLabel = name + "  §f" + src;
                Button rowBtn = Button.builder(Component.literal(""),
                        btn -> openChild(new ScreenControlScreen(screen.id())))
                        .bounds(left, ry(y), w, 20).build();
                addRenderableWidget(rowBtn);
                // 名称+片源整行超宽时悬停才滚动显示全部
                addRenderableWidget(new MarqueeText(left, ry(y), w, 20, rowBtn, fullLabel));
                y += 22;
            }
            y += 2;
        }

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                "§7提示：不带 Shift 直接按按键 = 对着屏幕时直接打开该屏控制；"
                        + "对着空气时打开全局设置。按键可在 选项→控制 中修改。",
                GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 14;

        // 底部固定操作区（与 TargetSearchScreen 一致：分页 + 返回上一级）
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

    /** 搜索过滤：屏幕名/自定义名/创建者（与 TargetSearchScreen 同一套规则）。 */
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
