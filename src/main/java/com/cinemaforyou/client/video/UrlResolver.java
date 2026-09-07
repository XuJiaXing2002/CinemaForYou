package com.cinemaforyou.client.video;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 视频源 URL 解析器。
 *
 * <p>输入可能是：
 * <ul>
 *   <li>直链：{@code https://example.com/video.mp4} → 原样返回</li>
 *   <li>本地文件：{@code file:/path/to/video.mp4} 或 {@code file:videos/foo.mp4}
 *       → 转为绝对路径</li>
 *   <li>YouTube/Twitch 等：{@code https://www.youtube.com/watch?v=...}
 *       → 调用 yt-dlp 解析为直链</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class UrlResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("CinemaForYou/UrlResolver");
    private static final Path DEBUG_LOG =
            Path.of("d:/Minecraft_Project/CinemaForYou/.dbg/trae-debug-log-video-link-stutter.ndjson");

    /** 直链视频扩展名（这些 URL 无需 yt-dlp 解析）。 */
    private static final Pattern DIRECT_VIDEO =
            Pattern.compile(".*\\.(mp4|mkv|webm|mov|avi|flv|wmv|ts|m2ts|mts|mpg|mpeg|3gp|"
                            + "mp3|wav|m4a|aac|ogg|flac|opus)(\\?.*)?$",
                    Pattern.CASE_INSENSITIVE);

    /** 服务端媒体库地址（/cinema/<base64令牌>，令牌无扩展名，需直接交给 FFmpeg）。 */
    private static final Pattern DIRECT_SERVER_MEDIA =
            Pattern.compile("(?i)^https?://[^/]+/cinema/[A-Za-z0-9_-]+$");

    /** HLS/DASH 流（也无需 yt-dlp，FFmpeg 可直接打开）。 */
    private static final Pattern DIRECT_STREAM =
            Pattern.compile(".*\\.(m3u8|mpd)(\\?.*)?$", Pattern.CASE_INSENSITIVE);

    private UrlResolver() {}

    /** 最近一次解析失败的原因（成功后清空），用于向玩家展示具体错误。 */
    private static volatile String lastError = null;

    /** 获取最近一次解析失败原因（无失败返回 null）。 */
    public static String getLastError() {
        return lastError;
    }

    private static void fail(String reason) {
        lastError = reason;
        LOGGER.error("[CinemaForYou] URL 解析失败: {}", reason);
    }

    /**
     * 解析视频源 URL 为 FFmpeg 可直接打开的视频/音频直链对。
     *
     * @return 解析结果（视频/音频直链），或 null 表示解析失败（原因见 {@link #getLastError()}）
     */
    public static ResolvedSource resolve(String sourceUrl) {
        lastError = null;
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            fail("URL 为空");
            return null;
        }

        // 剥掉首尾引号（聊天输入含空格路径时通常会加引号）
        String s = sourceUrl.trim();
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                        || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }

        // 裸本地路径自动识别（无需 file: 前缀）：C:\xx、C:/xx、UNC、实际存在的相对路径。
        // 否则会被误交给 yt-dlp 解析并触发无关的 Cookie 错误
        if (!s.startsWith("file:") && looksLikeLocalPath(s)) {
            s = "file:" + s;
        }

        // 本地文件
        if (s.startsWith("file:")) {
            String path = s.substring("file:".length());
            // 相对路径：基于游戏目录的 cinema/videos/ 解析
            File f = new File(path);
            if (!f.isAbsolute()) {
                f = new File(net.minecraft.client.Minecraft.getInstance().gameDirectory,
                        "cinema").toPath().resolve(path).toFile();
            }
            if (!f.exists()) {
                fail("本地文件不存在: " + f.getAbsolutePath());
                return null;
            }
            String abs = f.getAbsolutePath();
            return new ResolvedSource(abs, abs);
        }

        // 服务端媒体库令牌地址（跳过 yt-dlp，直接交给 FFmpeg）
        if (DIRECT_SERVER_MEDIA.matcher(s).matches()) {
            LOGGER.info("[CinemaForYou] 识别为服务端媒体库直链，直接交给 FFmpeg: {}", s);
            return new ResolvedSource(s, s);
        }

        // 直链视频
        if (DIRECT_VIDEO.matcher(s).matches() || DIRECT_STREAM.matcher(s).matches()) {
            return new ResolvedSource(s, s);
        }

        // rtsp/rtmp 等流协议
        if (s.startsWith("rtsp:") || s.startsWith("rtmp:")
                || s.startsWith("udp:") || s.startsWith("tcp:")) {
            return new ResolvedSource(s, s);
        }

        // 其它（YouTube/B站/Twitch 等）：用 yt-dlp 解析
        return resolveWithYtDlp(s);
    }

    /** 判断输入是否像本地文件路径（Windows 盘符、UNC、或实际存在的相对路径）。 */
    private static boolean looksLikeLocalPath(String s) {
        if (s.matches("[A-Za-z]:[\\\\/].*")) return true;      // C:\xx 或 C:/xx
        if (s.startsWith("\\\\")) return true;                 // UNC 路径 \\server\share
        try {
            return java.nio.file.Files.exists(java.nio.file.Path.of(s)); // 相对路径存在
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 构造 FFmpeg 拉流所需的自定义请求头（UA + Referer）。
     *
     * <p>B站 CDN 强校验 Referer 必须为 bilibili 域，部分站点校验浏览器 UA；
     * 非 HTTP(S) 源（本地文件/rtsp 等）返回 null（不需要设置）。
     *
     * @param sourceUrl   玩家输入的原始地址（页面 URL 或本地文件路径）
     * @param resolvedUrl 最终要打开的直链（http(s) 才需要头）
     */
    public static String ffmpegHttpHeaders(String sourceUrl, String resolvedUrl) {
        if (resolvedUrl == null
                || !(resolvedUrl.startsWith("http://") || resolvedUrl.startsWith("https://"))) {
            return null;
        }
        return "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36\r\n"
                + "Referer: " + refererFor(sourceUrl) + "\r\n";
    }

    /**
     * 根据原始 URL 推导 HTTP Referer。
     *
     * <p>B站 CDN 强校验 Referer 必须为 bilibili 域；其余站点用其自身 origin。
     */
    private static String refererFor(String sourceUrl) {
        if (sourceUrl == null) return "https://www.google.com/";
        String lower = sourceUrl.toLowerCase();
        if (lower.contains("bilibili.com") || lower.contains("b23.tv")) {
            return "https://www.bilibili.com/";
        }
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            return "https://www.youtube.com/";
        }
        if (lower.contains("twitch.tv")) {
            return "https://www.twitch.tv/";
        }
        try {
            java.net.URI uri = java.net.URI.create(sourceUrl);
            if (uri.getScheme() != null && uri.getHost() != null) {
                int port = uri.getPort();
                return uri.getScheme() + "://" + uri.getHost()
                        + (port > 0 ? ":" + port : "") + "/";
            }
        } catch (Exception ignored) {}
        return "https://www.google.com/";
    }

    /** 最近一次 yt-dlp 运行是否因浏览器 Cookie 解密失败（DPAPI）而退出。 */
    private static volatile boolean lastRunCookieDecryptFail = false;

    /**
     * 一次解析的最终结果：视频直链 + 音频直链。
     *
     * <p>多数站点（YouTube/B站高清等）给的是 DASH 分离流，yt-dlp {@code -g}
     * 会输出两行 URL：第一行视频、第二行音频；普通封装格式只有一行，此时
     * 音频直链与视频直链相同（由 FFmpeg 在同一文件里分别取流）。
     */
    public record ResolvedSource(String videoUrl, String audioUrl) {}

    /** 调用 yt-dlp 解析为直链（浏览器 Cookie 读取失败时自动以无 Cookie 重试一次）。 */
    private static ResolvedSource resolveWithYtDlp(String url) {
        File ytDlp = findYtDlpBinary();
        if (ytDlp == null || !ytDlp.exists()) {
            fail("yt-dlp 未安装（首次启动时自动下载，可能因网络受限失败；"
                    + "可手动下载 yt-dlp.exe 放到游戏目录 cinema/ 文件夹，或加入系统 PATH）");
            return null;
        }

        for (String formatSelector : selectFormats(url.toLowerCase())) {
            String[] urls = runYtDlp(ytDlp, url, true, formatSelector);
            if (urls != null) {
                return toResolvedSource(urls, url);
            }
            if (lastRunCookieDecryptFail) {
                // Chrome/Edge 127+ App-Bound 加密导致 --cookies-from-browser 失败：
                // 无 Cookie 重试一次，未限制视频仍可播放
                LOGGER.warn("[CinemaForYou] 浏览器 Cookie 读取失败（DPAPI），尝试无 Cookie 重试");
                urls = runYtDlp(ytDlp, url, false, formatSelector);
                if (urls != null) {
                    lastError = null;
                    LOGGER.info("[CinemaForYou] 无 Cookie 重试成功（该视频无需登录）");
                    return toResolvedSource(urls, url);
                }
            }
        }
        return null;
    }

    /** 把 yt-dlp 输出的 URL 行转换为视频/音频直链对。 */
    private static ResolvedSource toResolvedSource(String[] urls, String originalUrl) {
        if (urls == null || urls.length == 0) return null;
        String video = urls[0];
        // 分离流：第二行是音频直链；单行封装则音视频同源
        String audio = urls.length > 1 && !urls[1].equals(video) ? urls[1] : video;
        if (!video.equals(audio)) {
            LOGGER.info("[CinemaForYou] 站点返回音视频分离流（DASH），"
                    + "视频与音频将分别拉流: {}", trimForLog(originalUrl));
        }
        return new ResolvedSource(video, audio);
    }

    /** 单次 yt-dlp 运行。withCookies=false 时不携带任何 cookie 参数。 */
    private static String[] runYtDlp(File ytDlp, String url, boolean withCookies, String formatSelector) {
        lastRunCookieDecryptFail = false;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ytDlp.getAbsolutePath());
            cmd.add("--no-warnings");
            cmd.add("--no-playlist");
            cmd.add("--prefer-free-formats");
            cmd.add("--force-ipv4");
            // 伪装浏览器 UA：B站对非浏览器 UA 返回 412 Precondition Failed
            cmd.add("--user-agent");
            cmd.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            // cookies：cookies.txt 文件优先（Chrome/Edge 127+ App-Bound 加密
            // 导致 --cookies-from-browser 报 "Failed to decrypt with DPAPI"，
            // 见 yt-dlp #10927）
            if (withCookies) {
                java.nio.file.Path cookiesFile = getCookiesFile();
                if (cookiesFile != null && java.nio.file.Files.exists(cookiesFile)) {
                    cmd.add("--cookies");
                    cmd.add(cookiesFile.toAbsolutePath().toString());
                } else {
                    String cookiesFrom = getCookiesFromBrowser();
                    if (cookiesFrom != null && !cookiesFrom.isBlank()) {
                        cmd.add("--cookies-from-browser");
                        cmd.add(cookiesFrom.trim());
                    }
                }
            }
            // B站风控：页面/API 请求需带 Referer（配合浏览器 UA 缓解 412）
            String lowerUrl = url.toLowerCase();
            if (lowerUrl.contains("bilibili.com") || lowerUrl.contains("b23.tv")) {
                cmd.add("--add-headers");
                cmd.add("Referer: https://www.bilibili.com/");
            }
            if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")) {
                cmd.add("--extractor-args");
                cmd.add("youtube:player_client=android,web");
            }
            cmd.add("-f");
            cmd.add(formatSelector);
            cmd.add("-g");
            cmd.add(url);
            // #region debug-point A:yt-dlp-command
            debugPoint("A", "UrlResolver.runYtDlp:189",
                    "[DEBUG] yt-dlp command prepared",
                    "url", trimForLog(url),
                    "withCookies", withCookies,
                    "format", formatSelector,
                    "binary", ytDlp.getAbsolutePath());
            // #endregion

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() > 0) out.append('\n');
                    out.append(line);
                }
            }

            // 错误流（仅用于诊断，不阻塞）
            StringBuilder errBuf = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (errBuf.length() > 2000) break;
                    errBuf.append(line).append('\n');
                }
            }

            int exit = p.waitFor();
            if (exit != 0) {
                String errTail = errBuf.toString();
                String low = errTail.toLowerCase();
                // Cookie 读取失败（DPAPI 加密 / cookies.txt 格式错误）→ 可无 Cookie 重试
                lastRunCookieDecryptFail = low.contains("dpapi") || low.contains("failed to decrypt")
                        || low.contains("cookiejar") || low.contains("netscape");
                String msg = describeYtDlpError(exit, errTail);
                String note = cookiesFileNote;
                if (note != null) msg += " §c（注意：配置的 cookies.txt 已被忽略——" + note + "）";
                // #region debug-point A:yt-dlp-failure
                debugPoint("A", "UrlResolver.runYtDlp:225",
                        "[DEBUG] yt-dlp failed",
                        "url", trimForLog(url),
                        "withCookies", withCookies,
                        "exit", exit,
                        "format", formatSelector,
                        "stderr", trimForLog(errTail),
                        "stdout", trimForLog(out.toString()));
                // #endregion
                fail(msg);
                return null;
            }

            String result = out.toString().trim();
            // yt-dlp -g 输出解析：
            //   - 单行：封装格式直链（音视频同文件），一行即完整源
            //   - 多行：DASH 分离流（首行视频、次行音频），两行都要保留
            //     （旧实现只取第一行 → 音频直链被丢弃，高清流全部无声）
            List<String> lines = new ArrayList<>();
            for (String line : result.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty()) lines.add(t);
            }
            if (lines.isEmpty()) {
                fail("yt-dlp 输出为空（视频可能需要登录/地区限制，或链接不受支持）");
                return null;
            }
            String[] urls = lines.toArray(new String[0]);
            // #region debug-point A:yt-dlp-success
            debugPoint("A", "UrlResolver.runYtDlp:yt-dlp-success",
                    "[DEBUG] yt-dlp resolved direct urls",
                    "url", trimForLog(url),
                    "withCookies", withCookies,
                    "format", formatSelector,
                    "urlCount", urls.length,
                    "resolvedVideo", trimForLog(urls[0]),
                    "resolvedAudio", urls.length > 1 ? trimForLog(urls[1]) : "(same-as-video)",
                    "stdoutLines", lines.size());
            // #endregion
            LOGGER.info("[CinemaForYou] yt-dlp 解析成功: {} → {} 条直链",
                    url, urls.length);
            return urls;
        } catch (Exception e) {
            // #region debug-point A:yt-dlp-exception
            debugPoint("A", "UrlResolver.runYtDlp:254",
                    "[DEBUG] yt-dlp exception",
                    "url", trimForLog(url),
                    "withCookies", withCookies,
                    "error", String.valueOf(e));
            // #endregion
            fail("yt-dlp 执行异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * 按站点选择更稳的 yt-dlp format 表达式回退链。
     *
     * <p>策略说明：
     * <ul>
     *   <li>优先<strong>同时含音视频</strong>的封装流（一行 URL，FFmpeg 单连接搞定）；</li>
     *   <li>其次选 ≤1080p 的 bestvideo+bestaudio（两行 URL，客户端分别拉流，
     *       由 {@link ResolvedSource} 承接）——YouTube/B站高清几乎都是这种；</li>
     *   <li>最后的 {@code best} 兜底避免"Requested format is not available"。</li>
     * </ul>
     * 之前 B 站直接报 format 不可用的原因之一就是跳过封装流、
     * 只请求 dash 单流还要求它自带音轨。
     */
    private static List<String> selectFormats(String lowerUrl) {
        if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")) {
            return List.of(
                    "22/18/bestvideo[height<=?1080]+bestaudio/best",
                    "best"
            );
        }
        if (lowerUrl.contains("bilibili.com") || lowerUrl.contains("b23.tv")) {
            return List.of(
                    "b/best/bestvideo[height<=?1080]+bestaudio/best",
                    "best",
                    "bestvideo+bestaudio/best"
            );
        }
        // 默认：优先取同时带音频和视频的单流，拿不到再放宽到合并流与站点默认 best。
        return List.of(
                "best[acodec!=none][vcodec!=none]/bestvideo[height<=?1080]+bestaudio/best",
                "best"
        );
    }

    /** 查找可用 yt-dlp：优先游戏目录，其次系统 PATH。 */
    private static File findYtDlpBinary() {
        File bundled = YtDlpDownloader.getYtDlpPath();
        if (bundled != null && bundled.exists()) {
            return bundled;
        }
        try {
            String exe = System.getProperty("os.name", "").toLowerCase().contains("windows")
                    ? "yt-dlp.exe" : "yt-dlp";
            Process p = new ProcessBuilder("where", exe).start();
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            int exit = p.waitFor();
            if (exit == 0 && line != null && !line.isBlank()) {
                File f = new File(line.trim());
                if (f.exists()) return f;
            }
        } catch (Exception ignored) {}
        return bundled;
    }

    /** 读取客户端配置的 cookies 来源浏览器（如 "edge"/"chrome"/"firefox"，未配置返回 null）。 */
    private static String getCookiesFromBrowser() {
        try {
            if (com.cinemaforyou.CinemaForYouClient.clientConfig != null) {
                return com.cinemaforyou.CinemaForYouClient.clientConfig.ytDlpCookiesFromBrowser;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** cookies.txt 被忽略的原因（有效时为 null），附加到解析失败提示中。 */
    private static volatile String cookiesFileNote = null;

    /**
     * 读取并校验配置的 cookies.txt 路径。
     *
     * <p>自动剥离 UTF-8 BOM（记事本重存常见，会导致 yt-dlp 报
     * "http.cookiejar bug!"）；非 Netscape 格式（如 JSON 导出）直接忽略，
     * 原因记录在 {@link #cookiesFileNote} 供失败提示引用。
     */
    private static java.nio.file.Path getCookiesFile() {
        try {
            if (com.cinemaforyou.CinemaForYouClient.clientConfig == null) return null;
            java.nio.file.Path p =
                    com.cinemaforyou.CinemaForYouClient.clientConfig.resolveCookiesFile();
            cookiesFileNote = null;
            if (p == null || !java.nio.file.Files.exists(p)) return null;

            byte[] bytes = java.nio.file.Files.readAllBytes(p);
            if (bytes.length == 0) {
                cookiesFileNote = "文件为空";
                LOGGER.warn("[CinemaForYou] cookies.txt 是空文件，已忽略");
                return null;
            }
            // 剥 UTF-8 BOM：EF BB BF
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                    && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                byte[] fixed = new byte[bytes.length - 3];
                System.arraycopy(bytes, 3, fixed, 0, fixed.length);
                java.nio.file.Files.write(p, fixed);
                LOGGER.info("[CinemaForYou] cookies.txt 含 UTF-8 BOM，已自动剥离");
                bytes = fixed;
            }
            String head = new String(bytes, 0, Math.min(bytes.length, 512),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (!head.contains("Netscape") && !head.contains("HTTP Cookie File")) {
                cookiesFileNote = "不是 Netscape 格式（第一行应为 '# Netscape HTTP Cookie File'，"
                        + "请勿导出成 JSON）";
                LOGGER.warn("[CinemaForYou] cookies.txt 不是 Netscape 格式，已忽略。{}", cookiesFileNote);
                return null;
            }
            return p;
        } catch (Exception e) {
            cookiesFileNote = "读取失败: " + e;
            LOGGER.warn("[CinemaForYou] cookies.txt 校验失败: {}", e.toString());
            return null;
        }
    }

    /**
     * 把 yt-dlp 的原始错误翻译成可操作的中文提示。
     *
     * <p>覆盖常见风控/认证失败场景，原始错误尾部附带在后便于进一步排查。
     */
    private static String describeYtDlpError(int exit, String errTail) {
        String tail = errTail == null ? "" : errTail.toLowerCase();
        String detail = errTail.length() > 150
                ? errTail.substring(0, 150) : errTail;

        if (tail.contains("could not copy") || tail.contains("7271")) {
            return "无法读取 Chrome/Edge 的 Cookie 数据库（浏览器正在运行会锁库）。"
                    + "解决：①彻底退出 Chrome/Edge（含托盘后台进程）后重试；"
                    + "②推荐改用 cookies.txt 文件（设置里'cookies.txt文件'）。§7原始错误: " + detail;
        }
        if (tail.contains("cookiejar") || tail.contains("netscape")) {
            return "cookies.txt 格式无效（需 Netscape 格式）。请用扩展 'Get cookies.txt LOCALLY'"
                    + "导出（不要选 JSON），文件第一行应为 '# Netscape HTTP Cookie File'。"
                    + "§7原始错误: " + detail;
        }
        if (tail.contains("dpapi") || tail.contains("dapi") || tail.contains("app-bound")
                || tail.contains("10927")) {
            return "yt-dlp 无法读取浏览器Cookie（Chrome/Edge 127+ 加密，DPAPI错误）。"
                    + "解决：①用扩展'Get cookies.txt LOCALLY'导出cookies.txt，"
                    + "把路径填到设置里'cookies.txt文件'（推荐）；"
                    + "②或浏览器名改填firefox。 原始错误: " + detail;
        }
        if (tail.contains("confirm you") && tail.contains("bot")) {
            return "YouTube 反爬要求登录态。请在设置里配置 cookies（推荐 cookies.txt 方式，"
                    + "浏览器名填 firefox 亦可）。§7原始错误: " + detail;
        }
        if (tail.contains("412")) {
            return "B站风控拦截（HTTP 412）。请在设置里配置 cookies（访问过 bilibili.com 的"
                    + " firefox，或导出的 cookies.txt）。§7原始错误: " + detail;
        }
        if (tail.contains("login required") || tail.contains("sign in")) {
            return "该视频需要登录。请配置 cookies。§7原始错误: " + detail;
        }
        if (tail.contains("requested format is not available")) {
            return "当前站点返回的视频格式与你这次请求不兼容，已改用更兼容的格式策略。"
                    + "若仍失败，可先换一个公开视频链接再试。§7原始错误: " + detail;
        }
        if (tail.contains("is not a valid url")) {
            return "输入的不是有效链接。§7原始错误: " + detail;
        }
        return "yt-dlp 退出码 " + exit + ": " + detail;
    }

    // #region debug-point A:helper
    private static void debugPoint(String hypothesisId, String location, String msg, Object... kvPairs) {
        try {
            Files.createDirectories(DEBUG_LOG.getParent());
            StringBuilder json = new StringBuilder();
            json.append("{\"sessionId\":\"video-link-stutter\",\"runId\":\"post-fix\",\"hypothesisId\":\"")
                    .append(escapeJson(hypothesisId)).append("\",\"location\":\"")
                    .append(escapeJson(location)).append("\",\"msg\":\"")
                    .append(escapeJson(msg)).append("\",\"data\":{");
            for (int i = 0; i + 1 < kvPairs.length; i += 2) {
                if (i > 0) json.append(',');
                json.append('"').append(escapeJson(String.valueOf(kvPairs[i]))).append("\":\"")
                        .append(escapeJson(String.valueOf(kvPairs[i + 1]))).append('"');
            }
            json.append("},\"ts\":").append(System.currentTimeMillis()).append("}");
            Files.writeString(DEBUG_LOG, json.append(System.lineSeparator()).toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String trimForLog(String value) {
        if (value == null) return "";
        return value.length() <= 240 ? value : value.substring(0, 240) + "...";
    }
    // #endregion
}
