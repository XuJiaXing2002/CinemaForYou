package com.cinemaforyou.client.gui;

import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.network.CreateScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 原版风格 URL 输入屏（纯组件实现，与 26.2 ChatScreen 同款渲染路径）。
 *
 * <p>玩家用选择器物品选完两个对角点后弹出此屏。可选填自定义屏幕 ID（短名称，
 * 供命令引用），输入视频源 URL 后点击"播放"，发送 {@link CreateScreenPayload}
 * 给服务端创建屏幕并播放。
 *
 * <p>26.2 注意事项（均为实测踩坑）：
 * <ul>
 *   <li>输入框必须用 {@code addRenderableWidget} 注册，{@code addWidget} 只注册
 *       事件不注册渲染，输入框会"隐形"</li>
 *   <li>自定义绘制不要走 {@code extractor.text}，全部信息用按钮/输入框提示呈现</li>
 *   <li>{@code extractBackground} 默认渲染菜单全景图，游戏内需覆盖为空或半透明色块</li>
 * </ul>
 */
public class UrlInputScreen extends Screen {

    private final BlockPos corner1;
    private final BlockPos corner2;

    private EditBox nameField;
    private EditBox urlField;

    public UrlInputScreen(BlockPos corner1, BlockPos corner2) {
        super(Component.translatable("gui.cinemaforyou.url_input.title"));
        this.corner1 = corner1;
        this.corner2 = corner2;
        // 界面打开期间持续显示黄色范围预览框
        com.cinemaforyou.client.render.SelectionPreview.beginConfirming(corner1, corner2);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;

        // 角点信息（禁用按钮 = 纯文本标签，走已验证的组件渲染路径）
        Button cornersLabel = Button.builder(
                Component.literal("§7角点1: " + corner1.toShortString()
                        + "  →  角点2: " + corner2.toShortString()),
                btn -> {}
        ).bounds(cx - 155, cy - 52, 310, 16).build();
        cornersLabel.active = false;
        addRenderableWidget(cornersLabel);

        // 自定义 ID 输入框（可选）
        nameField = new EditBox(this.font, 300, 18,
                Component.translatable("gui.cinemaforyou.url_input.name"));
        nameField.setX(cx - 150);
        nameField.setY(cy - 28);
        nameField.setMaxLength(32);
        nameField.setHint(Component.literal("自定义 ID（可留空，如 main）"));
        addRenderableWidget(nameField);

        // URL 输入框
        urlField = new EditBox(this.font, 300, 18,
                Component.translatable("gui.cinemaforyou.url_input.url"));
        urlField.setX(cx - 150);
        urlField.setY(cy - 4);
        urlField.setMaxLength(512);
        urlField.setHint(Component.literal("https://... 或 videos/foo.mp4"));
        addRenderableWidget(urlField);
        setInitialFocus(urlField);

        // 创建并播放按钮（需填写视频链接；本地文件请用“浏览文件”选择）
        addRenderableWidget(Button.builder(
                Component.literal("创建并播放"),
                btn -> onCreateAndPlay()
        ).bounds(cx - 150, cy + 22, 95, 20).build());

        // 打开文件按钮
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cinemaforyou.url_input.browse"),
                btn -> Minecraft.getInstance().gui.setScreen(
                        new FileSelectScreen(corner1, corner2))
        ).bounds(cx - 50, cy + 22, 95, 20).build());

        // 取消按钮
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cinemaforyou.url_input.cancel"),
                btn -> onClose()
        ).bounds(cx + 50, cy + 22, 95, 20).build());

        // 设置按钮 + 创建空屏幕（无需视频源，稍后可再选片源播放）
        addRenderableWidget(Button.builder(
                Component.translatable("gui.cinemaforyou.url_input.settings"),
                btn -> Minecraft.getInstance().gui.setScreen(new CinemaSettingsScreen())
        ).bounds(cx - 150, cy + 48, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("创建空屏幕"),
                btn -> onCreateEmpty()
        ).bounds(cx + 5, cy + 48, 150, 20).build());
    }

    private void onCreateAndPlay() {
        String url = urlField.getValue().trim();
        if (url.isEmpty()) {
            // 给个可见反馈：聚焦 URL 框并提示
            urlField.setHint(Component.literal("§c请输入视频链接，或点下方“创建空屏幕”"));
            setInitialFocus(urlField);
            return;
        }
        // 发送 C2S 创建屏幕包（携带可选自定义 ID），创建后立即播放
        ClientNetworkHandlers.sendCreateScreen(
                new CreateScreenPayload(corner1, corner2, url, nameField.getValue().trim()));
        onClose();
    }

    /** 只创建空屏幕（不填视频源），之后可在该屏幕的 V 控制页选择片源。 */
    private void onCreateEmpty() {
        ClientNetworkHandlers.sendCreateScreen(
                new CreateScreenPayload(corner1, corner2, "", nameField.getValue().trim()));
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] 已创建空屏幕（未设置视频源）。"
                            + "对准屏幕按 V，可播放本地视频/链接或加入队列。"));
        }
        onClose();
    }

    /** 输入框内按 Enter 直接播放，ESC 关闭。 */
    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == 256) { // ESC
            onClose();
            return true;
        }
        if (key == 257 || key == 335) { // Enter / NumEnter
            onCreateAndPlay();
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * 覆盖默认背景：默认实现渲染菜单全景图，游戏内不合适。
     * 画半透明深色底，保证输入框文字可读。
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        extractor.fill(0, 0, this.width, this.height, 0x90101014);
    }

    /** 界面关闭（播放/取消/ESC）时清除黄色范围预览框。 */
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
