package com.cinemaforyou.manager;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.config.ServerConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;


import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端媒体 HTTP 服务（JDK 内置 HttpServer，无额外依赖）。
 *
 * <p>作用：让"本地视频"也能被服务器上其它玩家观看——
 * 视频文件放到 <strong>服务器目录/cinema/videos/</strong> 下后，
 * 播放本地文件时会自动改写成 {@code http://…/cinema/文件名}，
 * 每个玩家的客户端各自从服务器拉流解码（与播放网址视频同一套解码链路）。
 *
 * <p>支持 Range（seek/拖动）与 HEAD；只允许访问媒体目录内的直接文件。
 */
public final class MediaHttpServer {

    private static HttpServer server;
    private static File mediaDir;

    private MediaHttpServer() {}

    /** 列出媒体目录内的文件名（排序），用于"服务器媒体库"。 */
    public static java.util.List<String> listMediaFiles() {
        File[] children = mediaDirectory().listFiles();
        if (children == null) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (File f : children) {
            if (f.isFile()) {
                out.add(f.getName());
            }
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    /** 服务端媒体目录（服务器目录/cinema/videos）。 */
    public static synchronized File mediaDirectory() {
        if (mediaDir != null) return mediaDir;
        MinecraftServer server = CinemaForYou.screenManager != null
                ? CinemaForYou.screenManager.getServer() : null;
        if (server == null) {
            mediaDir = new File("cinema", "videos");
        } else {
            mediaDir = server.getServerDirectory().resolve("cinema/videos").toFile();
        }
        return mediaDir;
    }

    /** 启动（服务端启动后调用）。 */
    public static synchronized void start() {
        ServerConfig cfg = CinemaForYou.serverConfig;
        int port = (cfg != null) ? cfg.mediaHttpPort : 25566;
        if (port <= 0) {
            CinemaForYou.LOGGER.info("[CinemaForYou] 媒体 HTTP 服务已关闭（mediaHttpPort<=0）");
            return;
        }
        stop();
        try {
            File dir = mediaDirectory();
            Files.createDirectories(dir.toPath());
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", MediaHttpServer::handle);
            // 必须配线程池：JDK HttpServer 默认在单线程上串行处理请求，
            // 一个视频流的长连接会独占该线程，音频/跳转的后续请求永远排不到。
            // 用有上限的固定池而非无界 cached 池：客户端探测跳读风暴时，
            // 每个未读完的 Range 响应都会占住一个线程，无界池会被拖垮
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
            server.start();
            CinemaForYou.LOGGER.info("[CinemaForYou] 媒体 HTTP 服务已启动: 端口 {}, 目录 {}",
                    port, dir.getAbsolutePath());
        } catch (IOException e) {
            CinemaForYou.LOGGER.error("[CinemaForYou] 媒体 HTTP 服务启动失败（端口 {}）: {}",
                    port, e.toString());
            server = null;
        }
    }

    /** 停止（服务端停止前调用）。 */
    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ───────────── 地址改写 ─────────────

    /**
     * 若该源是本地文件且服务端媒体目录存在同名文件，
     * 改写为可由所有玩家拉取的 http 地址；否则原样返回。
     */
    public static String mapLocalFileToHttp(String sourceUrl) {
        if (sourceUrl == null || !sourceUrl.startsWith("file:")) return sourceUrl;
        String base = resolveBaseUrl();
        if (base == null) return sourceUrl;

        String path = sourceUrl.substring("file:".length());
        File f = new File(path);
        String name = f.getName();
        if (name.isEmpty() || name.contains("/") || name.contains("\\")) return sourceUrl;

        File media = new File(mediaDirectory(), name);
        if (!media.isFile()) {
            // 原文件可能已被自动转封装替换（.ts/.mkv → .mp4/.mkv）：改用优化版，
            // 让旧引用（历史记录/播放列表）在替换后依然能播
            File optimized = MediaRemuxer.findOptimized(mediaDirectory(), name);
            if (optimized == null) return sourceUrl; // 服务端没有此文件，保持原样（仅本机可见）
            name = optimized.getName();
            media = optimized;
        }

        // 用 Base64url 做文件名标识：纯字母数字与 - _，任何网络环境/隧道/代理都安全
        String token = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return base + "cinema/" + token;
    }

    /** 解析对外基础地址：配置优先，其次自动探测本机局域网 IPv4。 */
    private static String resolveBaseUrl() {
        ServerConfig cfg = CinemaForYou.serverConfig;
        if (cfg != null && cfg.mediaBaseUrl != null && !cfg.mediaBaseUrl.isBlank()) {
            String b = normalizeBaseUrl(cfg.mediaBaseUrl.trim());
            if (!b.endsWith("/")) b += "/";
            logBaseOnce("已配置 mediaBaseUrl=" + cfg.mediaBaseUrl.trim() + " → 规范化后=" + b);
            return b;
        }
        int port = (cfg != null) ? cfg.mediaHttpPort : 25566;
        if (port <= 0) return null;
        String lan = detectLanIpv4();
        String auto = lan != null ? "http://" + lan + ":" + port + "/" : null;
        if (auto != null) logBaseOnce("未配置 mediaBaseUrl，自动使用 " + auto);
        return auto;
    }

    /** 规范化用户填的外网地址：补协议、折叠重复协议前缀/杂散斜杠。 */
    private static String normalizeBaseUrl(String b) {
        if (b == null) return "";
        b = b.trim();
        // 修复形如 "http//:" / "http://http://" 的叠写
        b = b.replace("http//:", "http://").replace("https//:", "https://");
        while (b.matches("(?i)https?://https?://.*")) {
            b = b.replaceFirst("(?i)https?://", "");
        }
        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            b = "http://" + b;
        }
        return b;
    }

    private static boolean baseLogged = false;

    private static void logBaseOnce(String msg) {
        if (!baseLogged) {
            baseLogged = true;
            CinemaForYou.LOGGER.info("[CinemaForYou] 媒体地址: {}", msg);
        }
    }

    private static String detectLanIpv4() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ───────────── 请求处理 ─────────────

    private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
    /** 单个开放区间 Range 响应最大字节数（16MB），防止探测跳读白传大段数据。 */
    private static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

    /** 请求日志限流：探测跳读风暴时每分钟可能数百条，刷爆面板控制台。 */
    private static volatile long lastHttpLogMs = 0L;
    private static volatile int suppressedHttpLogs = 0;

    private static void logRequest(String method, String path, String range) {
        long now = System.currentTimeMillis();
        if (now - lastHttpLogMs >= 2000L) {
            lastHttpLogMs = now;
            int suppressed = suppressedHttpLogs;
            suppressedHttpLogs = 0;
            CinemaForYou.LOGGER.info("[CinemaForYou] 媒体 HTTP {} {} (Range={}){}",
                    method, path, range == null ? "-" : range,
                    suppressed > 0 ? " (+" + suppressed + " 条已合并)" : "");
        } else {
            suppressedHttpLogs++;
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/cinema/")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String token = path.substring("/cinema/".length());
            String rangeHeader0 = exchange.getRequestHeaders().getFirst("Range");
            logRequest(method, path, rangeHeader0);
            String name;
            try {
                byte[] decoded = java.util.Base64.getUrlDecoder().decode(token);
                name = new String(decoded, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..")) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            File file = new File(mediaDirectory(), name);
            File dir;
            try {
                dir = mediaDirectory().getCanonicalFile();
            } catch (IOException e) {
                dir = mediaDirectory();
            }
            if (!file.isFile()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            try {
                if (!file.getCanonicalFile().toPath().startsWith(dir.toPath())) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
            } catch (IOException e) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            long length = file.length();
            long start = 0;
            long end = length - 1;
            boolean partial = false;
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                Matcher m = RANGE.matcher(rangeHeader);
                if (m.matches()) {
                    String s = m.group(1);
                    String e = m.group(2);
                    if (s.isEmpty() && !e.isEmpty()) {
                        long suffix = Long.parseLong(e);
                        if (suffix > 0) {
                            start = Math.max(0, length - suffix);
                            end = length - 1;
                            partial = true;
                        }
                    } else if (!s.isEmpty()) {
                        start = Long.parseLong(s);
                        if (!e.isEmpty()) {
                            end = Long.parseLong(e);
                        }
                        if (start >= length) {
                            exchange.getResponseHeaders().set("Content-Range", "bytes */" + length);
                            exchange.sendResponseHeaders(416, -1);
                            return;
                        }
                        end = Math.min(end, length - 1);
                        partial = true;
                        // 客户端探测/跳读经常发"bytes=N-"(到文件尾)的开放区间，
                        // 一次只读几十 KB 就换偏移；若不封顶，服务端线程会一直
                        // 写到文件尾（可白白传出几十 MB/次），拖垮带宽与线程池。
                        // 封顶后客户端读完当前段自然会发下一个 Range，无缝衔接。
                        if (e.isEmpty() && end - start + 1 > MAX_RESPONSE_BYTES) {
                            end = start + MAX_RESPONSE_BYTES - 1;
                        }
                    }
                }
            }

            String contentType = guessType(name);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            long respondLength = partial ? (end - start + 1) : length;

            if (partial) {
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + end + "/" + length);
            }
            exchange.sendResponseHeaders(partial ? 206 : 200, method.equals("HEAD") ? -1 : respondLength);
            if (method.equals("HEAD")) {
                return;
            }
            try (OutputStream out = exchange.getResponseBody();
                 java.io.InputStream in = Files.newInputStream(file.toPath())) {
                if (start > 0) {
                    in.skipNBytes(start);
                }
                long remaining = respondLength;
                byte[] buf = new byte[64 * 1024];
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    out.write(buf, 0, n);
                    remaining -= n;
                }
            }
        } catch (Throwable t) {
            // 客户端中途断开等：忽略
        } finally {
            exchange.close();
        }
    }

    private static String guessType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".m4a")) {
            return "video/mp4";
        }
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".ts")) return "video/mp2t";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".aac")) return "audio/aac";
        return "application/octet-stream";
    }
}
