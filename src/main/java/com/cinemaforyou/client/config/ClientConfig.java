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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    /**
     * yt-dlp 网络代理地址（可选，留空 = 直连）。
     *
     * <p>用于 TikTok 等直连不通/被墙的站点：填上代理软件提供的本地端口即可，
     * 例如 {@code http://127.0.0.1:7890}（Clash 默认）或 socks5 代理
     * {@code socks5://127.0.0.1:1080}。解析时作为 {@code --proxy} 传给 yt-dlp。
     */
    public String ytDlpProxy = "";

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

    /**
     * 全局音频输出延迟补偿（毫秒）：声卡帧位置是"已交给混音器"的时间，实际出声
     * 还隔着系统混音/设备延迟。经实测多数机器设为 0 即对齐；若听感"声音比画面慢"，
     * 调大此值；若"画面比声音慢"，调小（不得为负）。
     */
    public double audioDeviceLatencyMs = 0.0;

    /** 每屏音频延迟补偿覆盖（屏幕 UUID → 毫秒；仅在与全局不同时记录）。 */
    public java.util.Map<String, Integer> screenAudioLatencyMs = new java.util.HashMap<>();

    // ───────────── 播放列表 / 历史（客户端本机） ─────────────

    /** 每屏"播完行为"：0=跟随全局默认 1=循环本片 2=自动播放下一个 3=播完暂停（键为屏幕 UUID）。 */
    public java.util.Map<String, Integer> screenPlayMode = new java.util.HashMap<>();

    /** "播完行为"全局默认：0=停止 1=循环本片 2=自动播放下一个 3=播完暂停。 */
    public int defaultPlayMode = 0;

    /**
     * 【旧版遗留】每屏本机播放队列（视频源 URL；键为屏幕 UUID）。
     *
     * <p>播放队列已迁移到服务端统一管理（服务端为唯一数据源），此字段仅用于
     * 首次同步时把用户已排的本机队列上传迁移；不再在代码中读写。
     */
    public java.util.Map<String, java.util.List<String>> screenPlaylist = new java.util.HashMap<>();

    /**
     * 【旧版遗留】每屏队列条目的附加信息（与 {@link #screenPlaylist} 同屏同下标一一对应：
     * 加队列的玩家名 + 加入时间毫秒）。仅用于旧队列迁移上传。
     */
    public java.util.Map<String, java.util.List<QueueItem>> screenPlaylistInfo = new java.util.HashMap<>();

    /** 旧版本机队列是否已迁移上传到服务端（true 后不再尝试上传）。 */
    public boolean queueMigratedToServer = false;

    /**
     * 服务端播放队列的客户端持久化副本（键 = 屏幕 UUID 字符串，值为该屏队列条目）。
     *
     * <p>服务端仍是队列唯一数据源：本字段只作本地缓存，重启游戏后服务端队列同步到达前，
     * 队列界面先显示这份副本；服务端全量广播到达后整体覆盖并落盘。客户端乐观更新
     * （删/清空/上移下移）也会即时更新并落盘。旧配置无此字段时为空 Map。
     */
    public java.util.Map<String, java.util.List<QueueItem>> queueMirror = new java.util.HashMap<>();

    /**
     * 全局播放队列的客户端持久化副本（总设置入口的「全局播放队列管理」）。
     *
     * <p>与 {@link #queueMirror} 同一套"服务端权威 + 本地只读副本"模型：服务端仍是唯一数据源，
     * 本字段只作本地缓存。全局队列条目不参与自动连播，手动点击才向所有屏幕发播放申请。
     */
    public java.util.List<QueueItem> globalQueueMirror = new java.util.ArrayList<>();

    /** 链接备注（URL → 玩家自命名，历史/队列显示时优先于标题与链接）。 */
    public java.util.Map<String, String> urlNotes = new java.util.HashMap<>();

    /** 链接标题缓存（URL → 视频标题，由 yt-dlp 后台抓取后持久化，避免重复请求）。 */
    public java.util.Map<String, String> urlTitles = new java.util.HashMap<>();

    /** 播放历史（全局，最新在前，最多 100 条）。 */
    public java.util.List<HistoryItem> history = new java.util.ArrayList<>();

    /**
     * 【旧版遗留】总设置曾经的"目标屏幕"（空串 = 未选择）。
     *
     * <p>总设置已改为"播放到所有屏幕"，代码不再引用此字段；保留仅为兼容旧配置文件。
     */
    public String lastTargetScreenId = "";

    /** 屏幕控制页"当前屏幕选择"（空串 = 未选择）。 */
    public String lastControlScreenId = "";

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

    /** 一条队列条目的附加信息（URL 之外：加队列的玩家名 + 加入时间毫秒）。 */
    public static class QueueItem {
        public String url = "";
        public String player = "";
        public long time = 0L;

        public QueueItem() {}

        public QueueItem(String url, String player, long time) {
            this.url = url == null ? "" : url;
            this.player = player == null ? "" : player;
            this.time = time;
        }
    }

    /** 某 URL 的备注（无备注返回 null）。 */
    public String noteFor(String url) {
        if (url == null || urlNotes == null) return null;
        String v = urlNotes.get(url.trim());
        return (v == null || v.isBlank()) ? null : v;
    }

    /** 设置某 URL 的备注（空串 = 清除），并保存。 */
    public void setNote(String url, String note) {
        if (url == null) return;
        if (urlNotes == null) urlNotes = new java.util.HashMap<>();
        String key = url.trim();
        if (note == null || note.isBlank()) {
            urlNotes.remove(key);
        } else {
            urlNotes.put(key, note.trim());
        }
        save();
    }

    /** 某 URL 的已缓存标题（无则返回 null）。 */
    public String titleFor(String url) {
        if (url == null || urlTitles == null) return null;
        String v = urlTitles.get(url.trim());
        return (v == null || v.isBlank()) ? null : v;
    }

    /** 记录某 URL 的标题缓存（空串 = 清除），并保存。 */
    public void setTitle(String url, String title) {
        if (url == null) return;
        if (urlTitles == null) urlTitles = new java.util.HashMap<>();
        String key = url.trim();
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            urlTitles.remove(key);
        } else {
            urlTitles.put(key, clean);
        }
        save();
    }

    /** 某屏音频延迟覆盖（未设置返回 null）。 */
    public Integer rawScreenLatencyMs(String screenId) {
        if (screenId == null || screenAudioLatencyMs == null) return null;
        return screenAudioLatencyMs.get(screenId);
    }

    /**
     * 某屏生效的音频延迟补偿：该屏有覆盖且与全局不同 → 用覆盖值；
     * 否则用全局值（覆盖等于全局时等同全局）。
     */
    public int effectiveAudioLatencyMs(String screenId) {
        Integer s = rawScreenLatencyMs(screenId);
        int g = (int) Math.max(0, Math.min(500, audioDeviceLatencyMs));
        if (s != null && s != g) {
            return Math.max(0, Math.min(500, s));
        }
        return g;
    }

    /** 设置某屏音频延迟覆盖（与全局相同则清除覆盖=跟随全局），并保存。 */
    public void setScreenAudioLatencyMs(String screenId, int ms) {
        if (screenId == null) return;
        if (screenAudioLatencyMs == null) screenAudioLatencyMs = new java.util.HashMap<>();
        int g = (int) Math.max(0, Math.min(500, audioDeviceLatencyMs));
        int v = Math.max(0, Math.min(500, ms));
        if (v == g) {
            screenAudioLatencyMs.remove(screenId);
        } else {
            screenAudioLatencyMs.put(screenId, v);
        }
        save();
    }

    /** 给 URL 生成人类可读的短名（备注 → 视频标题 → 文件名/链接截断）。 */
    public static String displayNameFor(String url) {
        if (url == null) return "";
        String s = url.trim();
        ClientConfig cfg = instance;
        if (cfg != null) {
            String note = cfg.noteFor(s);
            if (note != null) return note;
            String title = cfg.titleFor(s);
            if (title != null) return title;
        }
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

    /** 某屏实际生效的播完行为（0/未设置 = 跟随全局默认）。 */
    public int playModeFor(String screenId) {
        Integer m = screenPlayMode.get(screenId);
        int mode = (m == null || m == 0) ? defaultPlayMode : m;
        return Math.max(0, Math.min(3, mode));
    }

    /** 某屏原始设置（null = 跟随全局默认）。 */
    public Integer rawPlayMode(String screenId) {
        Integer m = screenPlayMode.get(screenId);
        return (m == null || m == 0) ? null : m;
    }

    /** 设置某屏的播完行为并保存。 */
    public void setPlayMode(String screenId, int mode) {
        if (screenPlayMode == null) screenPlayMode = new java.util.HashMap<>();
        screenPlayMode.put(screenId, Math.max(0, Math.min(3, mode)));
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

    /** 配置文件路径（config/cinemaforyou-client.json）。 */
    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("cinemaforyou-client.json");
    }

    /** 加载配置（文件不存在则生成默认）。在客户端初始化时调用。 */
    public static ClientConfig load() {
        Path file = configFile();

        if (!Files.exists(file)) {
            instance = new ClientConfig();
            writeJson(GSON.toJson(instance), file);
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
            if (instance.audioDeviceLatencyMs < 0) instance.audioDeviceLatencyMs = 0;
            if (instance.audioDeviceLatencyMs > 500) instance.audioDeviceLatencyMs = 500;
            if (instance.screenPlayMode == null) instance.screenPlayMode = new java.util.HashMap<>();
            if (instance.screenAudioLatencyMs == null) instance.screenAudioLatencyMs = new java.util.HashMap<>();
            if (instance.defaultPlayMode < 0 || instance.defaultPlayMode > 3) instance.defaultPlayMode = 0;
            if (instance.screenPlaylist == null) instance.screenPlaylist = new java.util.HashMap<>();
            if (instance.screenPlaylistInfo == null) instance.screenPlaylistInfo = new java.util.HashMap<>();
            // 本地队列副本：旧配置无此字段 → 空 Map（不报错）；并清理损坏的空列表/空条目
            if (instance.queueMirror == null) instance.queueMirror = new java.util.HashMap<>();
            instance.queueMirror.values().removeIf(java.util.Objects::isNull);
            for (java.util.List<QueueItem> mirrorItems : instance.queueMirror.values()) {
                mirrorItems.removeIf(java.util.Objects::isNull);
            }
            // 全局播放队列本地副本：旧配置无此字段 → 空列表；清理空条目/空 URL
            if (instance.globalQueueMirror == null) instance.globalQueueMirror = new java.util.ArrayList<>();
            instance.globalQueueMirror.removeIf(java.util.Objects::isNull);
            instance.globalQueueMirror.removeIf(i -> i.url == null || i.url.isEmpty());
            if (instance.urlNotes == null) instance.urlNotes = new java.util.HashMap<>();
            if (instance.urlTitles == null) instance.urlTitles = new java.util.HashMap<>();
            // 清理旧版编码错误缓存的乱码标题（含 U+FFFD 替换符），触发重新抓取
            instance.urlTitles.entrySet().removeIf(
                    e -> e.getValue() == null || e.getValue().contains("\uFFFD"));
            if (instance.history == null) instance.history = new java.util.ArrayList<>();
            LOGGER.info("[CinemaForYou] 客户端配置已加载: {}", file);
        } catch (IOException e) {
            LOGGER.error("[CinemaForYou] 客户端配置加载失败，使用默认值", e);
            instance = new ClientConfig();
        }
        return instance;
    }

    // ───────────── 异步 / 节流落盘状态 ─────────────

    /** 落盘节流间隔（毫秒）：短时间内的多次 {@link #save()} 合并，最多每 500ms 真正写盘一次。 */
    private static final long SAVE_THROTTLE_MS = 500;

    /**
     * 实际写盘的单线程调度器（守护线程）。
     *
     * <p>渲染线程 / 网络线程 / 后台标题线程调用 {@link #save()} 时只「标记脏 + 调度」，
     * 序列化与磁盘 IO 全部在 {@code CinemaForYou-Config-Save} 线程完成，不阻塞调用线程。
     */
    private static final ScheduledExecutorService SAVE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "CinemaForYou-Config-Save");
                t.setDaemon(true);
                return t;
            });

    /** 落盘状态锁：只保护下面 3 个状态字段，持锁时间极短（不做序列化与 IO）。 */
    private final Object saveStateLock = new Object();

    /** 写盘互斥锁：序列化成字符串与写文件都在此锁内，保证多个写盘请求互斥、快照一致。 */
    private final Object saveWriteLock = new Object();

    /** 存在尚未写盘的修改。 */
    private boolean dirty = false;

    /** 已有一个延后的写盘任务在排队（窗口内的后续 save() 合并到该任务）。 */
    private boolean saveScheduled = false;

    /** 上次真正写盘的时间戳（毫秒），用于计算节流延迟。 */
    private long lastSaveAtMs = 0L;

    /**
     * 保存当前配置（异步 + 节流）。
     *
     * <p>本方法立即返回、不做磁盘 IO：只标记「脏」并按节流窗口调度一次后台写盘，
     * 最多每 {@link #SAVE_THROTTLE_MS} 毫秒真正写文件一次（窗口内多次调用自动合并），
     * 避免 GUI 操作、队列同步广播等高频调用造成卡顿。
     *
     * <p>需要立即落盘的场景（断线 / 退出）请调用 {@link #flush()}。
     *
     * <p>线程安全：GUI 线程、网络线程、后台标题线程均可并发调用。
     */
    public void save() {
        long delay;
        synchronized (saveStateLock) {
            dirty = true;
            if (saveScheduled) return;   // 已有排队任务：合并到它，不重复调度
            saveScheduled = true;
            delay = Math.max(0L, SAVE_THROTTLE_MS - (System.currentTimeMillis() - lastSaveAtMs));
        }
        try {
            SAVE_EXECUTOR.schedule(this::scheduledSave, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 守护线程执行器不可用（理论不会发生）：退化为同步写，避免丢改动
            synchronized (saveStateLock) {
                saveScheduled = false;
            }
            LOGGER.warn("[CinemaForYou] 配置写盘任务无法调度，改为同步保存", e);
            writeNow();
        }
    }

    /**
     * 立即同步落盘（客户端退出 / 断开连接时由生命周期钩子调用）。
     *
     * <p>清零脏标记并立刻写一次当前最新快照；排队中的节流任务运行时检测到不脏会直接跳过。
     * 写盘异常只记日志，不向调用方抛出。
     */
    public void flush() {
        synchronized (saveStateLock) {
            dirty = false;
        }
        writeNow();
    }

    /** 后台节流任务入口：若期间已被 {@link #flush()} 落盘则跳过，避免重复写。 */
    private void scheduledSave() {
        synchronized (saveStateLock) {
            saveScheduled = false;
            if (!dirty) return;
        }
        writeNow();
    }

    /**
     * 真正写盘：持 {@link #saveWriteLock} 先把配置序列化成字符串快照，再写入文件。
     *
     * <p>先清脏再序列化：序列化期间其它线程的 {@link #save()} 会把脏标记重新置真，
     * 保证并发修改不会漏写（最多延后一个节流窗口再写一次）。
     */
    private void writeNow() {
        synchronized (saveWriteLock) {
            synchronized (saveStateLock) {
                dirty = false;
            }
            String json;
            try {
                // 持写锁序列化：与 flush() / 其它写盘任务互斥，得到一致的字符串快照，
                // 不会出现多个写盘线程交叉写出半更新 JSON 的情况
                json = GSON.toJson(this);
            } catch (Throwable t) {
                LOGGER.error("[CinemaForYou] 客户端配置序列化失败，本次不落盘", t);
                synchronized (saveStateLock) {
                    dirty = true;   // 保留脏标记，下次 save()/flush() 重试
                }
                return;
            }
            synchronized (saveStateLock) {
                lastSaveAtMs = System.currentTimeMillis();
            }
            writeJson(json, configFile());
        }
    }

    /**
     * 把 JSON 字符串写入配置文件：先写同名 {@code .tmp} 临时文件，再原子替换目标文件，
     * 避免写到一半崩溃/进程被杀导致配置损坏。异常只记日志，不向调用方抛出。
     */
    private static void writeJson(String json, Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                // 个别文件系统 / 安全软件占用时不支持原子移动：退化为普通覆盖
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("[CinemaForYou] 客户端配置保存失败", e);
        }
    }

    // ───────────── 便捷方法 ─────────────

    /** 默认音量映射到 0.0-1.0 浮点。 */
    public float volumeFloat() {
        return Math.max(0f, Math.min(1f, defaultVolume / 100f));
    }
}
