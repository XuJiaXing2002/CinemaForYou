package com.cinemaforyou.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端配置（JSON 文件，位于 {@code config/cinemaforyou-client.json}）。
 *
 * <p>首次启动时自动生成默认配置。玩家可手动编辑后重启生效。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code renderDistance} - 屏幕渲染距离（块），默认 128</li>
 *   <li>{@code defaultVolume} - 默认音量（0-100），默认 80</li>
 *   <li>{@code autoDownloadYtDlp} - 是否自动下载 yt-dlp，默认 true</li>
 *   <li>{@code textureFiltering} - 纹理过滤模式（linear/nearest），默认 linear</li>
 *   <li>{@code maxVideoResolution} - 最大解码分辨率（高度），默认 1080</li>
 *   <li>{@code showDebugInfo} - 是否在屏幕上显示调试信息，默认 false</li>
 *   <li>{@code ytDlpCookiesFromBrowser} - yt-dlp 读取 cookies 的浏览器（空=不使用）</li>
 *   <li>{@code localVideosDir} - 本地视频默认目录，默认 cinema/videos</li>
 *   <li>{@code showSelectionBox} - 是否显示对角点选择预览框，默认 true</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public class ClientConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("CinemaForYou/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ───────────── 配置字段 ─────────────

    /** 屏幕渲染距离（块）。超过此距离的屏幕不渲染。 */
    public int renderDistance = 128;

    /** 默认音量（0-100）。 */
    public int defaultVolume = 80;

    /** 是否自动下载 yt-dlp（用于解析 YouTube/Twitch 等流媒体 URL）。 */
    public boolean autoDownloadYtDlp = true;

    /** 纹理过滤模式："linear"（平滑）或 "nearest"（像素化）。 */
    public String textureFiltering = "linear";

    /** 最大解码分辨率（视频高度，如 720/1080/1440/2160）。 */
    public int maxVideoResolution = 1080;

    /** 是否在屏幕上显示调试信息（屏幕 ID、状态、位置）。 */
    public boolean showDebugInfo = false;

    /**
     * yt-dlp cookies 来源浏览器（"edge"/"chrome"/"firefox"/"brave"/"vivaldi" 等）。
     *
     * <p>留空 = 不使用 cookies。配置后 yt-dlp 从该浏览器读取登录态，可解决：
     * <ul>
     *   <li>YouTube "Sign in to confirm you're not a bot" 反爬</li>
     *   <li>B站 412 Precondition Failed（需要 buvid3 cookie）/ 登录视频 / 大会员清晰度</li>
     * </ul>
     *
     * <p>注意：读取 cookies 时需完全关闭对应浏览器（Windows 上 Chrome 系会锁定数据库）。
     */
    public String ytDlpCookiesFromBrowser = "";

    /** 本地视频默认目录（相对游戏目录或绝对路径），文件选择器与 file: 相对路径共用。 */
    public String localVideosDir = "cinema/videos";

    /**
     * cookies.txt 文件路径（可选，优先于 {@link #ytDlpCookiesFromBrowser}）。
     *
     * <p>Chrome/Edge 127+ 启用 App-Bound 加密后 yt-dlp 无法直接读取其 cookie
     * （yt-dlp issue #10927 "with DAPI" 错误）。此时推荐：
     * 用浏览器扩展 "Get cookies.txt LOCALLY" 导出 cookies.txt，
     * 把文件路径填到这里（支持相对游戏目录的路径）。
     */
    public String ytDlpCookiesFile = "";

    /** 是否显示对角点选择预览框（第一点选定后实时显示范围）。 */
    public boolean showSelectionBox = true;

    /**
     * 【调试用】屏幕渲染模式（默认 0 已修复黑色噪点，通常无需改动）：
     * 0 = 默认：视频纹理走【不透明实体管线】+ 三线性 mip 采样（推荐）
     * 1 = 纯白不透明色块（无纹理，双面）——隔离纹理问题
     * 2 = 纯白不透明色块（无纹理，单面）
     * 3 = 同模式 0（保留用于对比）
     * 4 = 视频纹理半透明管线但只画朝向相机的一面（旧问题路径，仅供调试）
     * 改完保存并重启游戏生效。
     */
    public int debugRenderMode = 0;

    // ───────────── 音频（全局默认，单屏可覆盖） ─────────────

    /** 声音最大可听距离（格）。超过此距离完全听不到。 */
    public int audioMaxDistance = 128;

    /** 声音距离衰减指数：1.0 = 线性；越大衰减越快，越小衰减越慢。 */
    public double audioFalloffExponent = 1.0;

    // ───────────── 播放列表 / 历史（客户端本机） ─────────────

    /** 每屏"播完行为"：0=停止 1=循环本片 2=自动播放下一个 3=播完暂停（键为屏幕 UUID）。 */
    public java.util.Map<String, Integer> screenPlayMode = new java.util.HashMap<>();

    /** 每屏播放队列（视频源 URL，按顺序自动播放；键为屏幕 UUID）。 */
    public java.util.Map<String, java.util.List<String>> screenPlaylist = new java.util.HashMap<>();

    /** 播放历史（全局，最新在前，最多 100 条）。 */
    public java.util.List<HistoryItem> history = new java.util.ArrayList<>();

    /** V 设置中"目标屏幕"（最近一次管理/播放用的屏幕 UUID，空串 = 自动选第一个）。 */
    public String lastTargetScreenId = "";

    /** 一条播放历史记录。 */
    public static class HistoryItem {
        public String url = "";
        public String name = "";
        public long time = 0L;

        public HistoryItem() {}

        public HistoryItem(String url, String name, long time) {
            this.url = url;
            this.name = name;
            this.time = time;
        }
    }

    /** 给 URL 生成人类可读的短名（本地文件取文件名，链接截断）。 */
    public static String displayNameFor(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.startsWith("file:")) {
            String p = s.substring(5).replace('\\', '/');
            int slash = p.lastIndexOf('/');
            String name = slash >= 0 ? p.substring(slash + 1) : p;
            return name.isEmpty() ? p : name;
        }
        return s.length() <= 64 ? s : s.substring(0, 61) + "...";
    }

    /** 追加一条历史（同 URL 去重置顶），并保存。 */
    public void addHistory(String url) {
        if (url == null || url.isEmpty()) return;
        if (history == null) history = new java.util.ArrayList<>();
        String name = displayNameFor(url);
        history.removeIf(i -> i.url != null && i.url.equals(url));
        history.add(0, new HistoryItem(url, name, System.currentTimeMillis()));
        while (history.size() > 100) {
            history.remove(history.size() - 1);
        }
        save();
    }

    /** 删除单条历史（按 URL），并保存。 */
    public void removeHistory(String url) {
        if (history == null || url == null) return;
        history.removeIf(i -> i.url != null && i.url.equals(url));
        save();
    }

    /** 清空全部播放历史，并保存。 */
    public void clearHistory() {
        if (history == null) return;
        history.clear();
        save();
    }

    /** 某屏的播放队列（不存在返回空列表）。 */
    public java.util.List<String> queueFor(String screenId) {
        java.util.List<String> q = screenPlaylist.get(screenId);
        return q != null ? q : java.util.Collections.emptyList();
    }

    /** 某屏当前播完行为（默认 0=停止）。 */
    public int playModeFor(String screenId) {
        Integer m = screenPlayMode.get(screenId);
        return m != null ? Math.max(0, Math.min(3, m)) : 0;
    }

    /** 设置某屏的播完行为并保存。 */
    public void setPlayMode(String screenId, int mode) {
        if (screenPlayMode == null) screenPlayMode = new java.util.HashMap<>();
        screenPlayMode.put(screenId, Math.max(0, Math.min(3, mode)));
        save();
    }

    /** 给某屏队列追加一条（去重）并保存。 */
    public void addToQueue(String screenId, String url) {
        if (url == null || url.isEmpty()) return;
        if (screenPlaylist == null) screenPlaylist = new java.util.HashMap<>();
        java.util.List<String> q = screenPlaylist.computeIfAbsent(screenId, k -> new java.util.ArrayList<>());
        if (!q.contains(url)) {
            q.add(url);
        }
        save();
    }

    /** 清空某屏队列并保存。 */
    public void clearQueue(String screenId) {
        if (screenPlaylist != null) {
            screenPlaylist.remove(screenId);
        }
        save();
    }

    /** 解析 cookies.txt 为绝对路径（未配置返回 null）。 */
    public java.nio.file.Path resolveCookiesFile() {
        if (ytDlpCookiesFile == null || ytDlpCookiesFile.isBlank()) return null;
        java.nio.file.Path p = java.nio.file.Path.of(ytDlpCookiesFile.trim());
        if (p.isAbsolute()) return p;
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(p);
    }

    /** 解析本地视频目录为绝对路径。 */
    public java.nio.file.Path resolveVideosDir() {
        java.nio.file.Path dir = java.nio.file.Path.of(localVideosDir == null ? "" : localVideosDir.trim());
        if (dir.isAbsolute()) return dir;
        return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(dir);
    }

    // ───────────── 加载 / 保存 ─────────────

    private static ClientConfig instance;

    public static ClientConfig get() {
        return instance;
    }

    /** 加载配置（文件不存在则生成默认）。在客户端初始化时调用。 */
    public static ClientConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve("cinemaforyou-client.json");

        if (!Files.exists(file)) {
            instance = new ClientConfig();
            save(instance, file);
            LOGGER.info("[CinemaForYou] 已生成默认客户端配置: {}", file);
            return instance;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            instance = GSON.fromJson(reader, ClientConfig.class);
            if (instance == null) {
                instance = new ClientConfig();
            }
            // 校验边界
            if (instance.renderDistance < 16) instance.renderDistance = 16;
            if (instance.renderDistance > 512) instance.renderDistance = 512;
            if (instance.defaultVolume < 0) instance.defaultVolume = 0;
            if (instance.defaultVolume > 100) instance.defaultVolume = 100;
            if (instance.audioMaxDistance < 8) instance.audioMaxDistance = 8;
            if (instance.audioMaxDistance > 512) instance.audioMaxDistance = 512;
            if (instance.audioFalloffExponent < 0.1) instance.audioFalloffExponent = 0.1;
            if (instance.audioFalloffExponent > 5.0) instance.audioFalloffExponent = 5.0;
            if (instance.screenPlayMode == null) instance.screenPlayMode = new java.util.HashMap<>();
            if (instance.screenPlaylist == null) instance.screenPlaylist = new java.util.HashMap<>();
            if (instance.history == null) instance.history = new java.util.ArrayList<>();
            LOGGER.info("[CinemaForYou] 客户端配置已加载: {}", file);
        } catch (IOException e) {
            LOGGER.error("[CinemaForYou] 客户端配置加载失败，使用默认值", e);
            instance = new ClientConfig();
        }
        return instance;
    }

    /** 保存当前配置到 config/cinemaforyou-client.json（设置界面调用）。 */
    public void save() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("cinemaforyou-client.json");
        save(this, file);
    }

    private static void save(ClientConfig config, Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[CinemaForYou] 客户端配置保存失败", e);
        }
    }

    // ───────────── 便捷方法 ─────────────

    /** 是否使用线性纹理过滤。 */
    public boolean isLinearFiltering() {
        return "linear".equalsIgnoreCase(textureFiltering);
    }

    /** 默认音量映射到 0.0-1.0 浮点。 */
    public float volumeFloat() {
        return Math.max(0f, Math.min(1f, defaultVolume / 100f));
    }
}
