package com.cinemaforyou.client.gui;

import java.util.ArrayList;
import java.util.List;

/** GUI 文字排版小工具：按字符数换行（中英文均按 1 字符计，宽度估算按每字符 6px）。 */
public final class UiText {

    private UiText() {}

    /** 按近似宽度（像素）把文本切成多行。 */
    public static List<String> wrap(String text, int widthPx) {
        int maxChars = Math.max(4, (widthPx - 14) / 6);
        return wrapChars(text, maxChars);
    }

    /** 按最大字符数换行（按码点计数，不拆代理对）。 */
    public static List<String> wrapChars(String text, int maxChars) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        int[] cps = text.codePoints().toArray();
        int start = 0;
        while (start < cps.length) {
            int end = Math.min(cps.length, start + maxChars);
            // 尽量不在英文单词中间断开
            if (end < cps.length && cps[end - 1] != ' ' && cps[end] == ' ') {
                end = end + 1; // 把空格带到下一行开头，避免断词
            }
            lines.add(new String(cps, start, end - start));
            start = end;
        }
        return lines;
    }

    /** 截断到指定像素宽度，超长加省略号。 */
    public static String fit(String text, int widthPx) {
        int maxChars = Math.max(3, (widthPx - 14) / 6);
        if (text.codePointCount(0, text.length()) <= maxChars) return text;
        return text.substring(0, text.offsetByCodePoints(0, maxChars - 1)) + "…";
    }
}
