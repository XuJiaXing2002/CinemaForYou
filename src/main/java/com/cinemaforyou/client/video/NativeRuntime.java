package com.cinemaforyou.client.video;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.bytedeco.javacpp.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * FFmpeg 解码原生库运行时保障（瘦身方案核心）。
 *
 * <p>自 1.0.6 起 jar 不再内置各平台 FFmpeg natives（原 121MB，现约 2MB）；
 * 首次需要解码时，从 Maven 镜像（阿里云 → repo1 回退）下载并解压与
 * JavaCV 严格匹配的 natives（本机平台一份，约 30MB，只下一次），随后通过
 * JavaCPP 官方支持的系统属性（{@code org.bytedeco.javacpp.platform.linkpath /
 * preloadpath}）注册为库搜索目录。
 *
 * <p>版本常量必须与 build.gradle 中的 javacv/ffmpeg 依赖版本一致，否则
 * natives 与 JNI 绑定不匹配会崩溃。
 */
@Environment(EnvType.CLIENT)
public final class NativeRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("CinemaForYou/NativeRuntime");

    private static final String JAVACPP_VERSION = "1.5.13";
    private static final String FFMPEG_VERSION = "8.0.1-1.5.13";

    /** 下载镜像（阿里云 central 对国内快；repo1 官方兜底）。 */
    private static final List<String> MIRRORS = List.of(
            "https://maven.aliyun.com/repository/central",
            "https://repo1.maven.org/maven2",
            "https://repo.maven.apache.org/maven2"
    );

    /**
     * 支持的原生库平台（与 gradle.properties 的 native_platforms 对应）。
     * 未知平台不尝试原生加载，走优雅降级（由上层提示"当前平台不支持视频解码"）。
     */
    private static final Set<String> SUPPORTED_PLATFORMS = Set.of(
            "windows-x86_64",
            "linux-x86_64", "linux-arm64",
            "macosx-x86_64", "macosx-arm64",
            "android-arm64", "android-x86_64");

    private static final Object LOCK = new Object();
    private static volatile boolean ready = false;
    private static volatile boolean running = false;
    private static volatile String lastFailure = null;

    private NativeRuntime() {}

    /** 客户端初始化时调用：后台开始准备 natives（不阻塞）。 */
    public static void startBackground() {
        synchronized (LOCK) {
            if (ready || running) return;
            running = true;
            Thread t = new Thread(NativeRuntime::ensureNow, "CinemaForYou-NativeRuntime");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 解码线程在创建 grabber 前调用：等待 natives 就绪。
     *
     * @return true=可直接解码；false=不可用（原因见 {@link #failureReason()}）
     */
    public static boolean ensureBlocking() {
        startBackground();
        synchronized (LOCK) {
            if (ready) return true;
            // 等待后台线程完成（下载有超时上限，最坏等待 ~数分钟）
            while (running) {
                try {
                    LOCK.wait(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return ready;
        }
    }

    /** 最近一次失败原因（成功或未尝试时为 null）。 */
    public static String failureReason() {
        return lastFailure;
    }

    private static void fail(String msg) {
        lastFailure = msg;
        LOGGER.error("[CinemaForYou] 解码原生库不可用: {}", msg);
    }

    private static void ensureNow() {
        try {
            Path ffDir;
            Path jcDir;
            try {
                String platform = detectPlatform();
                Path root = nativesRoot(platform);
                Path ffBase = root.resolve("org/bytedeco/ffmpeg/" + platform);
                Path jcBase = root.resolve("org/bytedeco/javacpp/" + platform);
                prepare(platform, root, ffBase, jcBase);
                ffDir = ffBase;
                jcDir = jcBase;
            } catch (Exception e) {
                fail("解码组件准备失败: " + e.getMessage());
                return;
            }
            if (!hasNativeLib(ffDir, "jniavutil")) {
                fail("解码组件文件缺失（目录 " + ffDir + "）");
                return;
            }
            // 必须在首次使用 JavaCPP 前设置（解码线程都先过 ensureBlocking）
            System.setProperty("org.bytedeco.javacpp.platform.linkpath",
                    ffDir.toAbsolutePath() + java.io.File.pathSeparator
                            + jcDir.toAbsolutePath());
            System.setProperty("org.bytedeco.javacpp.platform.preloadpath",
                    jcDir.toAbsolutePath() + java.io.File.pathSeparator
                            + ffDir.toAbsolutePath());
            synchronized (LOCK) {
                ready = true;
                lastFailure = null;
            }
            // 预热 libvpx 软解码器：首次 avcodec_open2 需要分配 vpx_codec_ctx 及
            // 初始化查找表，耗时可达数百毫秒到 1~2 秒。若在用户首次播放透明 WebM
            // 时才触发，会表现为"无画面、无声音、进度条走"。此处主动查找解码器
            // 触发 FFmpeg 侧的 lazy 初始化，首次播放即可直接出帧。
            try {
                org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name("libvpx-vp9");
                org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name("libvpx");
                LOGGER.info("[CinemaForYou] libvpx 解码器预热完成");
            } catch (Throwable t) {
                LOGGER.warn("[CinemaForYou] libvpx 预热失败（不影响播放，首次播放可能稍慢）", t);
            }
            LOGGER.info("[CinemaForYou] 解码原生库就绪: {} / {}", ffDir, jcDir);
        } catch (Throwable t) {
            fail("解码组件准备异常: " + t);
        } finally {
            // 无论成败都要释放等待中的解码线程（失败时 ensureBlocking 返回 false）
            synchronized (LOCK) {
                running = false;
                LOCK.notifyAll();
            }
        }
    }

    /** 确保 natives 已下载解压（标记文件或必需原生库缺失时联网获取）。 */
    private static void prepare(String platform, Path root, Path ffBase, Path jcBase)
            throws Exception {
        String marker = "ok-" + FFMPEG_VERSION + "-" + JAVACPP_VERSION;
        Path markerFile = root.resolve("cinemaforyou-natives-" + platform + ".txt");
        boolean have = Files.isRegularFile(markerFile)
                && Files.readString(markerFile).trim().equals(marker)
                && hasNativeLib(jcBase, "jnijavacpp");
        if (have) return;

        LOGGER.info("[CinemaForYou] 首次使用：下载解码组件（{}，约 30MB，仅一次）...", platform);
        // 目录不完整：清空重建
        if (Files.exists(root)) {
            deleteRecursively(root);
        }
        Files.createDirectories(jcBase);

        String ffArtifact = "org/bytedeco/ffmpeg/" + FFMPEG_VERSION
                + "/ffmpeg-" + FFMPEG_VERSION + "-" + platform + ".jar";
        String jcArtifact = "org/bytedeco/javacpp/" + JAVACPP_VERSION
                + "/javacpp-" + JAVACPP_VERSION + "-" + platform + ".jar";
        Path tmp = Files.createTempDirectory("cfy-natives-");
        try {
            Path ffJar = tmp.resolve("ffmpeg.jar");
            Path jcJar = tmp.resolve("javacpp.jar");
            download(ffArtifact, ffJar);
            download(jcArtifact, jcJar);
            extractNatives(ffJar, root);
            extractNatives(jcJar, root);
            Files.writeString(markerFile, marker);
            LOGGER.info("[CinemaForYou] 解码组件下载解压完成");
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** 依次尝试各镜像下载并做 SHA-1 校验（失败抛异常由上层报告）。 */
    private static void download(String artifact, Path target) throws Exception {
        Exception lastErr = null;
        for (String mirror : MIRRORS) {
            String url = mirror + "/" + artifact;
            try {
                Path shaTmp = target.resolveSibling(target.getFileName() + ".sha1");
                byte[] expect = readUrlBytes(url + ".sha1", 15_000);
                String expectHex = new String(expect, java.nio.charset.StandardCharsets.UTF_8)
                        .trim().split("\\s+")[0].toLowerCase();
                downloadTo(url, target);
                String actualHex = sha1(target);
                if (!expectHex.equals(actualHex)) {
                    throw new IOException("SHA-1 校验失败（期望 " + expectHex + "，实际 " + actualHex + "）");
                }
                return;
            } catch (Exception e) {
                lastErr = e;
                LOGGER.warn("[CinemaForYou] 镜像下载失败 {}: {}", url, e.toString());
            }
        }
        throw new IOException("全部镜像下载失败: " + lastErr);
    }

    private static void downloadTo(String url, Path target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(90_000);
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 CinemaForYou/1.0.6");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = c.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readUrlBytes(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 CinemaForYou/1.0.6");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        try (InputStream in = c.getInputStream()) {
            return in.readAllBytes();
        } finally {
            c.disconnect();
        }
    }

    /** 解压 jar 里的 natives（跳过 META-INF 与目录项）。 */
    private static void extractNatives(Path jar, Path root) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) continue; // 防路径穿越
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    zis.transferTo(os);
                }
            }
        }
    }

    private static String sha1(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static Path nativesRoot(String platform) {
        Minecraft mc = Minecraft.getInstance();
        Path base = mc.gameDirectory.toPath().resolve("cinema").resolve("natives");
        return base.resolve(platform);
    }

    /**
     * 检测当前平台（JavaCPP 平台名）。
     *
     * <p>优先级：显式属性 {@code org.bytedeco.javacpp.platform} &gt;
     * JavaCPP 自身识别 {@link Loader#getPlatform()}（Android 上得到 android-arm64/
     * android-x86_64）&gt; 原有 os.name/os.arch 回退映射。
     * 未知平台抛出可捕获异常（由 ensureNow 记录失败），上层据此提示
     * "当前平台不支持视频解码"，而不是让原生库直接崩溃。
     */
    private static String detectPlatform() {
        // 1) 显式覆盖优先级最高（JavaCPP 自身也读取同一属性），此时不再做 Android 纠正
        String override = System.getProperty("org.bytedeco.javacpp.platform", "").trim();
        String platform;
        if (!override.isEmpty()) {
            platform = override;
        } else {
            // 2) 优先用 JavaCPP 自身识别：其 Detector 依据 java.vm.name/os.name/os.arch，
            //    安卓（dalvik）会判为 android-*，桌面判为 windows/linux/macosx-*
            platform = null;
            try {
                String p = Loader.getPlatform();
                if (p != null && !p.isBlank()) {
                    platform = p.trim();
                }
            } catch (Throwable t) {
                LOGGER.warn("[CinemaForYou] JavaCPP 平台识别失败，回退 os.name/os.arch 映射", t);
            }
            if (platform == null) {
                platform = mapFromOs();
            }
            // Android 纠正：FCL/Pojav 等启动器实际运行 OpenJDK，java.vm.name 不以 dalvik
            // 开头，JavaCPP 会误判成 linux-arm64；再用 Android 环境变量兜底纠正。
            if (platform != null && platform.startsWith("linux-") && isAndroidRuntime()) {
                platform = "android-" + platform.substring("linux-".length());
            }
        }
        // 3) 校验：非桌面常见平台且非 Android 视为未知平台，走优雅降级
        if (platform == null || !SUPPORTED_PLATFORMS.contains(platform)) {
            throw new IllegalStateException("当前平台不支持视频解码: "
                    + (platform != null ? platform
                    : System.getProperty("os.name", "?") + "/" + System.getProperty("os.arch", "?")));
        }
        LOGGER.info("[CinemaForYou] 解码平台识别为 {} (os.name={}, os.arch={})",
                platform, System.getProperty("os.name", "?"), System.getProperty("os.arch", "?"));
        return platform;
    }

    /** 原有 os.name/os.arch 映射（JavaCPP 识别不可用时的回退）；未知系统返回 null。 */
    private static String mapFromOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win")) {
            return "windows-x86_64";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return (arch.contains("aarch64") || arch.contains("arm64"))
                    ? "macosx-arm64" : "macosx-x86_64";
        }
        if (os.contains("linux")) {
            return (arch.contains("aarch64") || arch.contains("arm64"))
                    ? "linux-arm64" : "linux-x86_64";
        }
        return null;
    }

    /**
     * Android 运行时探测。
     *
     * <p>JavaCPP 仅在 {@code java.vm.name} 以 dalvik 开头时才识别 Android，而
     * FCL/PojavLauncher 等启动器运行的是 OpenJDK，会被误判为 linux-arm64。
     * Android 系统进程普遍带有 ANDROID_ROOT/ANDROID_DATA 环境变量（桌面 Linux
     * 不会命中），据此兜底纠正平台名。
     */
    private static boolean isAndroidRuntime() {
        try {
            String root = System.getenv("ANDROID_ROOT");
            if (root != null && !root.isEmpty()) return true;
            String data = System.getenv("ANDROID_DATA");
            if (data != null && !data.isEmpty()) return true;
            return Files.isRegularFile(Path.of("/system/build.prop"));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 目录下是否存在指定基名的原生库（兼容 Windows/Linux/Android 与 macOS 命名）。 */
    private static boolean hasNativeLib(Path dir, String base) {
        return Files.isRegularFile(dir.resolve(base + ".dll"))
                || Files.isRegularFile(dir.resolve("lib" + base + ".so"))
                || Files.isRegularFile(dir.resolve("lib" + base + ".dylib"));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {}
            });
        }
    }
}
