package com.cinemaforyou.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 通用单行文本输入屏（用于屏幕改名、描述编辑等）。
 */
public class InputValueScreen extends ScrollableSettingsScreen {

    private final String title;
    private final String initial;
    private final int maxLength;
    private final Consumer<String> onSave;
    private EditBox field;

    public InputValueScreen(String title, String initial, int maxLength, Consumer<String> onSave) {
        super(Component.literal(title));
        this.title = title;
        this.initial = initial == null ? "" : initial;
        this.maxLength = Math.max(1, maxLength);
        this.onSave = onSave;
    }

    @Override
    protected void init() {
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
        int w = Math.min(300, this.width - 40);
        int y = 2;

        addRenderableWidget(new GuiTextLabel(cx, ry(y), w, 12,
                "§e" + title, GuiTextLabel.Align.CENTER, GuiTextLabel.YELLOW));
        y += 20;

        field = new EditBox(this.font, cx - w / 2, ry(y), w, 20,
                Component.literal(title));
        field.setValue(initial);
        field.setMaxLength(maxLength);
        addRenderableWidget(field);
        y += 28;

        int half = w / 2 - 3;
        addRenderableWidget(Button.builder(Component.literal("§a保存"),
                btn -> {
                    String v = field.getValue();
                    if (v == null) v = "";
                    if (onSave != null) {
                        onSave.accept(v);
                    }
                    // 返回打开本输入页的界面
                    if (!GuiNav.back(this)) {
                        Minecraft.getInstance().gui.setScreen(null);
                    }
                }
        ).bounds(cx - w / 2, ry(y), half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"),
                btn -> onClose()
        ).bounds(cx + 3, ry(y), half, 20).build());
        y += 26;

        finishContent(y);
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
