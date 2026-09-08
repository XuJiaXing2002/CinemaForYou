package com.cinemaforyou.config;

import com.cinemaforyou.CinemaForYou;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 服务端配置（JSON 文件，位于 {@code config/cinemaforyou-server.json}）。
 *
 * <p>首次启动时自动生成默认配置。服务端管理员可手动编辑后重启生效。
 *
 * <p>配置项：
 * <ul>
 *   <li>{@code maxScreenArea} - 单屏幕最大面积（块²），默认 1024</li>
 *   <li>{@code maxScreensPerPlayer} - 每玩家最大屏幕数，默认 10</li>
 *   <li>{@code syncIntervalMs} - 状态广播校准间隔（毫秒），默认 5000</li>
 *   <li>{@code allowLocalFiles} - 是否允许播放本地文件，默认 true</li>
 *   <li>{@code allowedDomains} - 允许的视频网站域名白名单（空列表 = 全部允许），默认空</li>
 *   <li>{@code requireOpForCreate} - 是否仅 op 可创建屏幕，默认 false</li>
 * </ul>
 */
public class ServerConfig {

    private static final Logger LOGGER = CinemaForYou.LOGGER;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ───────────── 配置字段 ─────────────

    /** 单屏幕最大面积（块²）。 */
    public int maxScreenArea = 32 * 32;

    /** 每玩家最大屏幕数。 */
    public int maxScreensPerPlayer = 10;

    /** 状态广播校准间隔（毫秒）。 */
    public int syncIntervalMs = 5000;

    /** 是否允许播放本地文件。 */
    public boolean allowLocalFiles = true;

    /** 允许的视频网站域名白名单（空列表 = 全部允许）。 */
    public List<String> allowedDomains = Collections.emptyList();

    /** 是否仅 op 可创建屏幕。 */
    public boolean requireOpForCreate = false;

    // ───────────── 媒体服务器（让其它玩家也能看本地视频） ─────────────

    /**
     * 内置媒体 HTTP 服务端口（0 = 关闭）。
     *
     * <p>把视频文件放进 服务器目录/cinema/videos/ 后，播放本地文件时若服务端
     * 也存在同名文件，会自动把地址改写成 http://… 让所有玩家都能拉流观看。
     */
    public int mediaHttpPort = 25566;

    /**
     * 客户端访问媒体服务器的地址（默认空 = 自动探测本机局域网 IP）。
     *
     * <p>服务器在公网/端口转发时请填外网可达地址，例如
     * {@code http://你的域名:25566/}（末尾斜杠可省略）。
     */
    public String mediaBaseUrl = "";

    // ───────────── 自动转封装（大文件 .ts/.mkv 等 → mp4，减少拉流请求数） ─────────────

    /**
     * 是否自动把大体积 .ts/.mkv/.flv 等文件后台转封装为 mp4（moov 前置），
     * 转完自动删除原文件。null/缺省 = 开启；false = 关闭。仅专用服务器生效。
     */
    public Boolean autoRemux;

    /** 触发自动转封装的最小文件大小（MB；<=0 = 默认 64）。 */
    public int remuxMinSizeMB = 0;

    // ───────────── 加载 / 保存 ─────────────

    /** 单例。 */
    private static ServerConfig instance;

    public static ServerConfig get() {
        return instance;
    }

    /** 加载配置（文件不存在则生成默认）。在服务端启动时调用。 */
    public static ServerConfig load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path file = configDir.resolve("cinemaforyou-server.json");

        if (!Files.exists(file)) {
            instance = new ServerConfig();
            save(instance, file);
            LOGGER.info("[CinemaForYou] 已生成默认服务端配置: {}", file);
            return instance;
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            instance = GSON.fromJson(reader, ServerConfig.class);
            if (instance == null) {
                instance = new ServerConfig();
            }
            LOGGER.info("[CinemaForYou] 服务端配置已加载: {}", file);
        } catch (IOException e) {
            LOGGER.error("[CinemaForYou] 服务端配置加载失败，使用默认值", e);
            instance = new ServerConfig();
        }
        return instance;
    }

    private static void save(ServerConfig config, Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("[CinemaForYou] 服务端配置保存失败", e);
        }
    }

    // ───────────── 便捷方法 ─────────────

    /**
     * 检查 URL 是否通过域名白名单。
     *
     * @param url 视频源 URL
     * @return true 表示允许
     */
    public boolean isUrlAllowed(String url) {
        if (allowedDomains == null || allowedDomains.isEmpty()) {
            return true; // 空白名单 = 全部允许
        }
        for (String domain : allowedDomains) {
            if (url.contains(domain)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查是否允许播放本地文件。
     *
     * @param url 视频源 URL
     * @return true 表示允许
     */
    public boolean isLocalFileAllowed(String url) {
        if (!allowLocalFiles && url.startsWith("file:")) {
            return false;
        }
        return true;
    }
}
