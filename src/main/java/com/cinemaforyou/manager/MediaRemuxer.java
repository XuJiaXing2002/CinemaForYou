package com.cinemaforyou.manager;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.config.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端自动转封装：大体积 .ts/.mkv/.flv 等文件在首次播放前于后台转封装为
 * moov 前置的 mp4（失败自动回退 mkv），完成后删除原文件并自动开始播放。
 *
 * <p>为什么需要：FFmpeg 客户端打开部分旧容器（尤其 moov/索引在文件尾的封装）
 * 时要做大量探测跳读——每跳一次就是一次新的 HTTP Range 请求，本地无感，
 * 但远程玩家每次都要穿隧道往返，几百次小请求会慢到像卡死。转封装为
 * mp4(+faststart) 后客户端变成干净的顺序拉流，往返开销骤降。
 *
 * <p>转封装是纯流拷贝（ffmpeg -c copy），不重编码，画质音质无损。
 * ffmpeg 可执行文件在首次需要时自动下载（GitHub ffmpeg-static 直链 →
 * johnvansickle 静态包 → 系统 PATH 回退）；全部失败则直接播放原文件，
 * 该功能对正常播放流程零影响。
 */
public final class MediaRemuxer {

    private static final List<String> CANDIDATE_EXTS =
            List.of("ts", "m2ts", "mts", "mkv", "flv", "avi", "wmv", "mpg", "mpeg", "mov", "3gp");
    /** 触发转封装的最小文件大小（配置未设时）。 */
    private static final long DEFAULT_MIN_BYTES = 64L * 1024 * 1024;

    /** 后台串行工作线程：下载工具 → 转封装（同文件多播放请求只跑一个任务）。 */
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CinemaForYou-Remux");
        t.setDaemon(true);
        return t;
    });

    /** 正在排队/运行中的任务：原文件名 → 等待自动播放的请求。 */
    private static final Map<String, List<Deferred>> pending = new ConcurrentHashMap<>();

    private static volatile File ffmpegBinary = null;
    private static volatile boolean ffmpegResolved = false;
    private static final Object FFMPEG_LOCK = new Object();
    private static volatile boolean localOnlyLogged = false;

    private record Deferred(UUID screenId, UUID playerId) {}

    private MediaRemuxer() {}

    // ───────────── 公共工具（MediaHttpServer 映射回退也用） ─────────────

    /** 是否为值得转封装的候选容器（按扩展名）。 */
    public static boolean isRemuxCandidate(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : CANDIDATE_EXTS) {
            if (lower.endsWith("." + ext)) return true;
        }
        return false;
    }

    /** 去掉最后扩展名的文件名（"a.b.ts" → "a.b"）。 */
    public static String baseName(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i > 0 ? fileName.substring(0, i) : fileName;
    }

    /**
     * 查找已生成的优化版（优先 mp4、其次 mkv），用于：映射回退
     * （原 .ts 引用在文件被替换后仍能播放）与转封装前的重复检查。
     */
    public static File findOptimized(File dir, String originalName) {
        String base = baseName(originalName);
        if (!base.equals(originalName)) {
            File mp4 = new File(dir, base + ".mp4");
            if (mp4.isFile()) return mp4;
            File mkv = new File(dir, base + ".mkv");
            if (mkv.isFile()) return mkv;
        }
        return null;
    }

    // ───────────── 播放拦截（ScreenManager.play 调用，服务端线程） ─────────────

    /**
     * 若该文件需要自动转封装，则排入后台任务并返回 true（本次播放延后，
     * 完成后自动播放）。返回 false 表示走正常播放流程。
     */
    public static boolean maybeDeferPlay(UUID screenId, ServerPlayer requester, String fileName) {
        try {
            if (requester == null || fileName == null) return false;
            ServerConfig cfg = CinemaForYou.serverConfig;
            if (cfg != null && Boolean.FALSE.equals(cfg.autoRemux)) return false;
            if (!isRemuxCandidate(fileName)) return false;

            File dir = MediaHttpServer.mediaDirectory();
            File src = new File(dir, fileName);
            if (!src.isFile()) return false;
            // 已有优化版：映射回退即可直接播放，无需转封装
            if (findOptimized(dir, fileName) != null) return false;

            MinecraftServer srv = server();
            if (srv == null || !srv.isDedicatedServer()) {
                // 单机/局域网：文件在本地硬盘，跳读开销可忽略，不需要转封装
                if (!localOnlyLogged) {
                    localOnlyLogged = true;
                    CinemaForYou.LOGGER.info("[CinemaForYou] 自动转封装仅对专用服务器生效（单机本地 HTTP 无需优化）");
                }
                return false;
            }

            long min = (cfg != null && cfg.remuxMinSizeMB > 0)
                    ? cfg.remuxMinSizeMB * 1024L * 1024L : DEFAULT_MIN_BYTES;
            if (src.length() < min) return false;

            List<Deferred> list = pending.computeIfAbsent(fileName, k -> new ArrayList<>());
            boolean newJob = list.isEmpty();
            list.add(new Deferred(screenId, requester.getUUID()));
            if (newJob) {
                CinemaForYou.LOGGER.info("[CinemaForYou] 自动转封装任务入队: {} ({}MB)",
                        fileName, src.length() / 1024 / 1024);
                WORKER.submit(() -> runJob(fileName, src));
            }
            send(requester, "§e⏳ 大文件首次播放将自动转封装（无损，仅首次等待，"
                    + (newJob ? "" : "正在转封装中") + "完成后自动开始播放）");
            return true;
        } catch (Throwable t) {
            CinemaForYou.LOGGER.warn("[CinemaForYou] 自动转封装拦截异常，走正常播放: {}", t.toString());
            return false;
        }
    }

    // ───────────── 后台任务 ─────────────

    private static void runJob(String fileName, File src) {
        File finalTarget = null;
        String note = null;
        try {
            File ff = ensureFfmpeg();
            if (ff == null) {
                finish(fileName, false, null, "§cffmpeg 不可用（自动下载失败），将直接播放原文件");
                return;
            }
            if (!src.isFile()) {
                finish(fileName, false, null, "§c原文件已不存在，取消转封装");
                return;
            }
            File dir = src.getParentFile();
            File workDir = new File(new File(dir, "tools"), "work");
            try {
                Files.createDirectories(workDir.toPath());
            } catch (IOException ignored) {}
            String base = baseName(fileName);

            // 目标扩展名优先 mp4（+faststart 索引前置）；编码不兼容时回退 mkv
            for (String ext : new String[]{"mp4", "mkv"}) {
                File target = new File(dir, base + "." + ext);
                if (target.isFile()) {
                    // 优化版已存在（例如上次删原文件失败残留）：直接沿用
                    finalTarget = target;
                    break;
                }
                File tmp = new File(workDir, base + "_tmp." + ext);
                if (!runFfmpeg(ff, src, tmp, ext)) {
                    continue;
                }
                try {
                    Files.move(tmp.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    try {
                        Files.move(tmp.toPath(), target.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e2) {
                        CinemaForYou.LOGGER.warn("[CinemaForYou] 移动优化文件失败: {}", e2.toString());
                        continue;
                    }
                }
                // 转封装成功：删除原文件（占用/失败时保留，不影响播放）
                if (!src.delete()) {
                    CinemaForYou.LOGGER.warn("[CinemaForYou] 原文件删除失败（可能被占用），保留: {}",
                            src.getName());
                }
                CinemaForYou.LOGGER.info("[CinemaForYou] 转封装完成: {} → {}", fileName, target.getName());
                finalTarget = target;
                break;
            }
            if (finalTarget == null) {
                finish(fileName, false, null, "§c转封装失败（编码与容器不兼容？），将直接播放原文件");
                return;
            }
            finish(fileName, true, finalTarget.getName(), null);
        } catch (Throwable t) {
            CinemaForYou.LOGGER.warn("[CinemaForYou] 转封装任务异常: {}", t.toString());
            finish(fileName, false, null, "§c转封装出错，将直接播放原文件");
        }
    }

    /** 执行一次 ffmpeg 流拷贝（mp4 附加 faststart）。 */
    private static boolean runFfmpeg(File ff, File src, File out, String ext) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ff.getAbsolutePath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(src.getAbsolutePath());
        cmd.add("-map");
        cmd.add("0");
        cmd.add("-c");
        cmd.add("copy");
        if (ext.equals("mp4")) {
            cmd.add("-movflags");
            cmd.add("+faststart");
        }
        cmd.add(out.getAbsolutePath());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (InputStream is = p.getInputStream()) {
            // 读掉输出避免管道阻塞（出错信息截尾保留进日志）
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = is.read(buf)) > 0) {
                total += n;
            }
        }
        int exit = p.waitFor();
        if (exit != 0 || !out.isFile() || out.length() < 4096) {
            CinemaForYou.LOGGER.warn("[CinemaForYou] 转封装 {}→{} 失败(exit={})", src.getName(), out.getName(), exit);
            try { Files.deleteIfExists(out.toPath()); } catch (IOException ignored) {}
            return false;
        }
        return true;
    }

    // ───────────── 完成回调（跳到服务端线程） ─────────────

    private static void finish(String fileName, boolean ok, String finalName, String failMsg) {
        MinecraftServer srv = server();
        if (srv == null) {
            pending.remove(fileName);
            return;
        }
        srv.execute(() -> {
            List<Deferred> defs = pending.remove(fileName);
            if (defs == null) return;
            ScreenManager mgr = CinemaForYou.screenManager;
            for (Deferred d : defs) {
                try {
                    ServerPlayer p = srv.getPlayerList().getPlayer(d.playerId());
                    boolean wantPlay = mgr != null && mgr.isScreenActive(d.screenId());
                    if (p != null) {
                        if (ok) {
                            send(p, "§a✅ 已自动优化为 " + finalName
                                    + "（原文件已删除）" + (wantPlay ? "，开始播放" : ""));
                        } else {
                            send(p, failMsg);
                        }
                    }
                    if (wantPlay && mgr != null) {
                        mgr.play(d.screenId(), "file:" + (ok ? finalName : fileName), p);
                    }
                } catch (Throwable t) {
                    CinemaForYou.LOGGER.warn("[CinemaForYou] 转封装完成后自动播放失败: {}", t.toString());
                }
            }
        });
    }

    // ───────────── ffmpeg 获取（首次惰性下载） ─────────────

    private static File ensureFfmpeg() throws Exception {
        if (ffmpegResolved) return ffmpegBinary;
        synchronized (FFMPEG_LOCK) {
            if (ffmpegResolved) return ffmpegBinary;
            boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            File tools = new File(MediaHttpServer.mediaDirectory().getParentFile(), "tools");
            File bin = new File(tools, win ? "ffmpeg.exe" : "ffmpeg");
            File resolved = null;
            try {
                Files.createDirectories(tools.toPath());
                if (bin.exists() && verify(bin)) {
                    resolved = bin;
                } else {
                    resolved = findSystemFfmpeg(win);
                    if (resolved == null) {
                        // GitHub ffmpeg-static 直链（免解压）
                        String url = win
                                ? "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.0/ffmpeg-win32-x64"
                                : "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.0/ffmpeg-linux-x64";
                        try {
                            download(url, bin.toPath());
                            makeExecutable(bin);
                            if (!verify(bin)) {
                                try { Files.deleteIfExists(bin.toPath()); } catch (IOException ignored) {}
                                bin.deleteOnExit();
                                bin = new File(tools, "ffmpeg");
                            }
                        } catch (Exception e) {
                            CinemaForYou.LOGGER.warn("[CinemaForYou] ffmpeg 直链下载失败，尝试备用源: {}", e.toString());
                        }
                        if (bin.exists() && verify(bin)) {
                            resolved = bin;
                        }
                    }
                }
            } finally {
                ffmpegResolved = true;
                ffmpegBinary = resolved;
            }
            if (resolved == null) {
                CinemaForYou.LOGGER.warn("[CinemaForYou] 自动转封装不可用（未找到/无法下载 ffmpeg），将直接播放原文件");
            } else {
                CinemaForYou.LOGGER.info("[CinemaForYou] ffmpeg 就绪: {}", resolved.getAbsolutePath());
            }
            return resolved;
        }
    }

    private static File findSystemFfmpeg(boolean win) {
        try {
            String pathEnv = System.getenv("PATH");
            if (pathEnv == null) return null;
            String name = win ? "ffmpeg.exe" : "ffmpeg";
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (dir == null || dir.isBlank()) continue;
                File f = new File(dir, name);
                if (f.isFile()) return f;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 下载到临时文件后原子移动到目标。 */
    private static void download(String url, Path target) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(15))
                .GET()
                .build();
        HttpResponse<Path> resp = client.send(req,
                HttpResponse.BodyHandlers.ofFile(target.resolveSibling(target.getFileName() + ".part")));
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        Files.move(target.resolveSibling(target.getFileName() + ".part"), target,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void makeExecutable(File f) {
        try {
            Files.setPosixFilePermissions(f.toPath(),
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (Exception ignored) {
            try {
                new ProcessBuilder("chmod", "+x", f.getAbsolutePath()).start();
            } catch (Exception ignored2) {}
        }
    }

    /** 运行 ffmpeg -version 验证可执行。 */
    private static boolean verify(File f) {
        try {
            Process p = new ProcessBuilder(f.getAbsolutePath(), "-version")
                    .redirectErrorStream(true).start();
            byte[] buf = new byte[1024];
            int n = p.getInputStream().read(buf);
            p.waitFor();
            if (n > 0) {
                String head = new String(buf, 0, Math.min(n, buf.length), StandardCharsets.UTF_8);
                return head.contains("ffmpeg version");
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ───────────── 小工具 ─────────────

    private static MinecraftServer server() {
        try {
            ScreenManager mgr = CinemaForYou.screenManager;
            return mgr == null ? null : mgr.getServer();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void send(ServerPlayer p, String msg) {
        if (p != null) {
            p.sendSystemMessage(Component.literal("[CinemaForYou] " + msg));
        }
    }
}
