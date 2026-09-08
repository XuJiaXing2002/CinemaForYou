package com.cinemaforyou.client.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * 悬停才滚动的完整文字行控制层（不自行绘制文字，只切换下层按钮 message）。
 *
 * <p>滚动用"字符窗口匀速推进"模拟（按钮原生滚动无法改起点/循环/速度）：
 * <ul>
 *   <li>未悬停：按钮显示恰好放得下的开头（静止、无省略号）；</li>
 *   <li>悬停：从开头开始，按固定节奏（每 70ms 前进 1 个字符，无加减速）
 *       逐字向右揭示到尾部 → 尾部停顿 → 回到开头循环；</li>
 *   <li>每次移开再移入都从头开始；颜色码（§x）随窗口完整保留。</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class MarqueeText extends AbstractWidget {

    /** 每个字符推进的间隔（毫秒）与头/尾停顿。 */
    private static final long STEP_MS = 1890L;  // 每字符 1890ms（再慢 3 倍）
    private static final long PAUSE_HEAD_MS = 700L;
    private static final long PAUSE_TAIL_MS = 900L;

    private final Button target;
    /** 全部字符（不含颜色码）。 */
    private final String plain;
    /** 每个字符前需要插入的当前颜色码（随 §x 变化）。 */
    private final char[][] plainCodes;
    private final int totalWidth;
    private final String headMsg;

    private boolean lastHovered = false;
    private int cursor = 0;          // 当前窗口起始字符下标
    private long stateMs = 0L;       // 当前状态已持续时长
    private int phase = 0;           // 0=头停 1=推进 2=尾停
    private String currentMsg = null;

    public MarqueeText(int x, int y, int width, int height, Button target, String text) {
        super(x, y, width, height, Component.literal(""));
        this.target = target;
        String raw = text == null ? "" : text;
        Font font = Minecraft.getInstance().font;
        StringBuilder sb = new StringBuilder(raw.length());
        StringBuilder codes = new StringBuilder();
        StringBuilder curCodes = new StringBuilder();
        java.util.List<char[]> codeList = new java.util.ArrayList<>();
        int total = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7' && i + 1 < raw.length()) {
                char code = Character.toLowerCase(raw.charAt(i + 1));
                i++;
                curCodes.setLength(0);
                curCodes.append('\u00a7').append(code);
                continue;
            }
            sb.append(c);
            codeList.add(curCodes.toString().toCharArray());
            total++;
        }
        this.plain = sb.toString();
        this.plainCodes = codeList.toArray(new char[0][]);
        this.totalWidth = font.width(plain);
        int headW = Math.max(8, width - 8);
        this.headMsg = plain.length() == 0 ? "" : sliceMsg(0, windowEnd(0, headW));
        target.setMessage(Component.literal(headMsg));
        this.currentMsg = headMsg;
    }

    /** 取 [start,end) 的字符并补齐所在颜色码，构成可显示串。 */
    private String sliceMsg(int start, int end) {
        StringBuilder out = new StringBuilder();
        char[] lastCode = null;
        for (int i = start; i < end && i < plain.length(); i++) {
            char[] code = plainCodes[i];
            if (code.length > 0 && !java.util.Arrays.equals(code, lastCode)) {
                out.append(code);
                lastCode = code;
            }
            out.append(plain.charAt(i));
        }
        return out.toString();
    }

    /** 从 start 起能容纳的最多字符数（像素不超过 maxPx，至少 1 个）。 */
    private int windowEnd(int start, int maxPx) {
        Font font = Minecraft.getInstance().font;
        int w = 0;
        int i = start;
        int last = start + 1;
        for (; i < plain.length(); i++) {
            w += font.width(String.valueOf(plain.charAt(i)));
            if (w > maxPx) break;
            last = i + 1;
        }
        return Math.max(start + 1, last);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor extractor,
                                            int mouseX, int mouseY, float partialTick) {
        boolean hovered = isHovered;
        if (hovered && !lastHovered) {
            // 每次悬停都从头开始
            cursor = 0;
            phase = 0;
            stateMs = 0L;
            String msg = sliceMsg(0, windowEnd(0, maxSliceW()));
            if (!msg.equals(currentMsg)) {
                currentMsg = msg;
                target.setMessage(Component.literal(msg));
            }
        } else if (!hovered && lastHovered) {
            // 移开：恢复开头并复位
            cursor = 0;
            phase = 0;
            stateMs = 0L;
            if (!headMsg.equals(currentMsg)) {
                currentMsg = headMsg;
                target.setMessage(Component.literal(headMsg));
            }
        }
        lastHovered = hovered;
        if (!hovered || plain.length() == 0 || totalWidth <= maxSliceW()) {
            return; // 不超宽时静止（初始已是完整/开头文字）
        }
        stateMs += 16L;
        switch (phase) {
            case 0 -> { // 头部停顿后开始推进
                if (stateMs >= PAUSE_HEAD_MS) {
                    phase = 1;
                    stateMs = 0L;
                }
            }
            case 1 -> { // 每 STEP_MS 前进一个字符；剩余内容不足一窗时进入尾部停顿
                if (stateMs >= STEP_MS) {
                    stateMs = 0L;
                    int start = cursor;
                    int end = windowEnd(start, maxSliceW());
                    String msg = sliceMsg(start, end);
                    if (end >= plain.length()) {
                        phase = 2; // 尾部整段已显示，停顿后回到开头
                        cursor = start;
                    } else {
                        cursor = start + 1;
                    }
                    setMsg(msg);
                }
            }
            case 2 -> { // 尾部停顿后回到开头
                if (stateMs >= PAUSE_TAIL_MS) {
                    cursor = 0;
                    phase = 0;
                    stateMs = 0L;
                    setMsg(sliceMsg(0, windowEnd(0, maxSliceW())));
                }
            }
        }
    }

    private void setMsg(String msg) {
        if (msg == null || msg.equals(currentMsg)) return;
        currentMsg = msg;
        target.setMessage(Component.literal(msg));
    }

    private int maxSliceW() {
        return Math.max(8, this.getWidth() - 8);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClicked) {
        return false; // 让下层按钮接收点击
    }

    @Override
    protected void updateWidgetNarration(
            net.minecraft.client.gui.narration.NarrationElementOutput narrationOutput) {
        this.defaultButtonNarrationText(narrationOutput);
    }
}
