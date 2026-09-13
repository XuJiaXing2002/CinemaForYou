package com.cinemaforyou.client.util;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 调试日志工具。
 *
 * <p>统一把调试点写入 {@code <游戏目录>/cinema/debug/<yyyy-MM-dd>.ndjson}
 * （本地日期，例如 {@code 2026-09-12.ndjson}）。日志为 NDJSON，每行一条 JSON：
 * {@code sessionId}/{@code runId}/{@code hypothesisId}/{@code location}/{@code msg}/
 * {@code data}/{@code ts}。
 *
 * <p>目录不存在时自动创建；任何写入失败都静默忽略，避免影响游戏主流程。
 */
@Environment(EnvType.CLIENT)
public final class DebugLog {

    /** 调试会话标识（保持历史值不变）。 */
    private static final String SESSION_ID = "video-link-stutter";

    /** 日志文件名日期格式（本地日期）。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private DebugLog() {
    }

    /** 当天调试日志路径：{@code <游戏目录>/cinema/debug/<yyyy-MM-dd>.ndjson}。 */
    private static Path currentLogPath() {
        String fileName = LocalDate.now().format(DATE_FORMAT) + ".ndjson";
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("cinema").resolve("debug").resolve(fileName);
    }

    /**
     * 追加一条调试日志。
     *
     * @param runId        本次运行标识（各调用点保持原值）
     * @param hypothesisId 假设编号
     * @param location     代码位置标识
     * @param msg          日志消息
     * @param kvPairs      自定义键值对（键值交替，长度不足时忽略末尾单项）
     */
    public static void debugPoint(
            String runId, String hypothesisId, String location, String msg, Object... kvPairs) {
        try {
            Path log = currentLogPath();
            Files.createDirectories(log.getParent());
            StringBuilder json = new StringBuilder();
            json.append("{\"sessionId\":\"").append(escapeJson(SESSION_ID))
                    .append("\",\"runId\":\"").append(escapeJson(runId))
                    .append("\",\"hypothesisId\":\"").append(escapeJson(hypothesisId))
                    .append("\",\"location\":\"").append(escapeJson(location))
                    .append("\",\"msg\":\"").append(escapeJson(msg)).append("\",\"data\":{");
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                if (i > 0) json.append(',');
                json.append('"').append(escapeJson(String.valueOf(kvPairs[i]))).append("\":\"")
                        .append(escapeJson(String.valueOf(kvPairs[i + 1]))).append('"');
            }
            json.append("},\"ts\":").append(System.currentTimeMillis()).append("}");
            Files.writeString(log, json.append(System.lineSeparator()).toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    /** JSON 字符串转义。 */
    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
