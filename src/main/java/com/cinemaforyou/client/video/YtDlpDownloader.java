package com.cinemaforyou.client.video;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * yt-dlp 二进制管理器。
 *
 * <p>首次启动时异步从 GitHub releases 下载对应平台的 yt-dlp 二进制到
 * {@code <游戏目录>/cinema/yt-dlp[.exe]}。
 *
 * <p>yt-dlp 用于解析 YouTube/Twitch 等流媒体网站的 URL 为 FFmpeg 可播放的直链。
 */
@Environment(EnvType.CLIENT)
public final class YtDlpDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger("CinemaForYou/YtDlp");

    private static final String YT_DLP_VERSION_TAG = "2026.08.18";
    private static volatile boolean downloaded = false;
    private static volatile boolean downloadFailed = false;

    private YtDlpDownloader() {}

    /** 异步触发下载（不阻塞主线程）。在 {@code onInitializeClient} 中调用。 */
    public static void ensureDownloadedAsync() {
        if (downloaded || downloadFailed) return;
        Thread t = new Thread(() -> {
            try {
                ensureDownloaded();
            } catch (Exception e) {
                downloadFailed = true;
                LOGGER.error("[CinemaForYou] yt-dlp 下载失败", e);
            }
        }, "CinemaForYou-YtDlp-Download");
        t.setDaemon(true);
        t.start();
    }

    /** 同步下载（阻塞）。依次尝试 GitHub 直连与多个镜像。 */
    public static void ensureDownloaded() throws Exception {
        if (downloaded) return;
        File target = getYtDlpPath();
        if (target == null) {
            downloadFailed = true;
            return;
        }
        if (target.exists() && target.canExecute()) {
            downloaded = true;
            LOGGER.info("[CinemaForYou] yt-dlp 已存在: {}", target.getAbsolutePath());
            return;
        }

        java.util.List<String> urls = getDownloadUrls();
        if (urls.isEmpty()) {
            downloadFailed = true;
            LOGGER.error("[CinemaForYou] 不支持的平台，跳过 yt-dlp 下载");
            return;
        }

        LOGGER.info("[CinemaForYou] 正在下载 yt-dlp {}...", YT_DLP_VERSION_TAG);
        target.getParentFile().mkdirs();
        Exception lastError = null;
        for (String downloadUrl : urls) {
            try {
                LOGGER.info("[CinemaForYou] 尝试下载源: {}", downloadUrl);
                try (InputStream in = openStreamWithRedirect(downloadUrl, 5);
                     FileOutputStream out = new FileOutputStream(target)) {
                    in.transferTo(out);
                }
                if (!isWindows()) {
                    target.setExecutable(true, false);
                }
                // 完整性粗检：非 Windows ELF 或 Windows PE 头
                if (target.length() < 1_000_000) {
                    throw new Exception("文件过小 (" + target.length() + " 字节)，疑似下载不完整");
                }
                downloaded = true;
                LOGGER.info("[CinemaForYou] yt-dlp 下载完成: {} ({} MB)",
                        target.getAbsolutePath(), target.length() / 1_000_000);
                return;
            } catch (Exception e) {
                lastError = e;
                LOGGER.warn("[CinemaForYou] 下载源失败: {} ({})", downloadUrl, e.getMessage());
                if (target.exists()) target.delete(); // 清理半截文件再试下一个源
            }
        }
        downloadFailed = true;
        throw (lastError != null) ? lastError : new Exception("所有下载源均失败");
    }

    /** 获取 yt-dlp 二进制路径（可能不存在）。 */
    public static File getYtDlpPath() {
        File cinemaDir = new File(net.minecraft.client.Minecraft.getInstance().gameDirectory, "cinema");
        String name = isWindows() ? "yt-dlp.exe" : "yt-dlp";
        return new File(cinemaDir, name);
    }

    /** yt-dlp 是否就绪。 */
    public static boolean isReady() {
        if (downloadFailed) return false;
        File f = getYtDlpPath();
        return f != null && f.exists() && f.canExecute();
    }

    // ───────────── 内部 ─────────────

    /**
     * 返回候选下载 URL 列表（按优先级）。
     *
     * <p>GitHub 直连在国内网络经常失败，因此附带多个镜像前缀作为回退。
     */
    private static java.util.List<String> getDownloadUrls() {
        String github = "https://github.com/yt-dlp/yt-dlp/releases/download/"
                + YT_DLP_VERSION_TAG + "/";
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean aarch64 = arch.contains("aarch64") || arch.contains("arm64");
        String asset;
        if (isWindows()) {
            asset = "yt-dlp.exe";
        } else {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac")) {
                asset = aarch64 ? "yt-dlp_macos" : "yt-dlp_macos_legacy";
            } else if (os.contains("linux")) {
                asset = aarch64 ? "yt-dlp_linux_aarch64" : "yt-dlp_linux";
            } else {
                return java.util.Collections.emptyList();
            }
        }
        String target = github + asset;
        // 直连优先，失败后依次尝试国内可达镜像
        return java.util.Arrays.asList(
                target,
                "https://gh-proxy.com/" + target,
                "https://ghproxy.net/" + target,
                "https://mirror.ghproxy.com/" + target
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /** 带重定向跟随的 HTTP 下载（GitHub releases 会 302）。 */
    private static InputStream openStreamWithRedirect(String url, int maxRedirects) throws Exception {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "CinemaForYou/1.0");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(300000);
        return conn.getInputStream();
    }
}
