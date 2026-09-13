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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * FFmpeg 解码原生库运行时保障（瘦身方案核心）。
 *
 * <p>自 1.0.6 起 jar 不再内置各平台 FFmpeg natives（原 121MB，现约 2MB）；
 * 首次需要解码时，从 Maven 镜像（阿里云 → repo1 回退）下载并解压与
 * JavaCV 严格匹配的 natives（本机平台一份，约 30MB，只下一次），随后通过
 * JavaCPP 官方支持的系统属性（{@code org.bytedeco.javacpp.platform.linkpath /
 * preloadpath}）注册为库搜索目录。
 *
 * <p>安卓上原生库根目录必须位于应用内部可执行目录（详见 {@link #nativesRoot}），
 * 否则 noexec 外部存储会导致 dlopen 失败。
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
            "macosx-x86_64", "macosx-arm64");

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
            // 安卓端不再适配视频解码（实测画面不可用、且软解负载高容易卡顿）：
            // 这里直接拒绝，不下载也不加载原生库，避免手机端因解码而卡死。
            if (isAndroidRuntime()) {
                fail("安卓端暂不支持视频解码");
                return;
            }
            String platform;
            Path root;
            Path ffDir;
            Path jcDir;
            try {
                platform = detectPlatform();
                root = nativesRoot(platform);
                ffDir = root.resolve("org/bytedeco/ffmpeg/" + platform);
                jcDir = root.resolve("org/bytedeco/javacpp/" + platform);
                // 诊断：明确实际使用的原生库根目录（安卓应位于可执行内部目录）
                LOGGER.info("[CinemaForYou] 原生库根目录: {} (platform={})",
                        root.toAbsolutePath(), platform);
            } catch (Exception e) {
                fail("解码组件准备失败: " + e.getMessage());
                return;
            }
            try {
                prepare(platform, root, ffDir, jcDir);
            } catch (Exception e) {
                fail("解码组件准备失败（平台 " + platform + "）: " + e.getMessage());
                return;
            }
            // 文件完整性：按平台后缀确认两个关键原生库确实存在于各自目录
            boolean ffOk = nativeLibExists(ffDir, platform, "jniavutil");
            boolean jcOk = nativeLibExists(jcDir, platform, "jnijavacpp");
            if (!ffOk || !jcOk) {
                // 失败自愈：之前失败可能留下半成品平台目录并被后续当成缓存，
                // 先删除该平台目录再重试一次（重新下载解压）。
                LOGGER.warn("[CinemaForYou] 完整性校验未通过（jniavutil={}, jnijavacpp={}），"
                        + "删除 {} 后重试一次", ffOk, jcOk, root.toAbsolutePath());
                try {
                    deleteRecursively(root);
                } catch (IOException e) {
                    LOGGER.warn("[CinemaForYou] 删除半成品目录失败: {}", e.toString());
                }
                try {
                    prepare(platform, root, ffDir, jcDir);
                } catch (Exception e) {
                    fail("解码组件准备失败（平台 " + platform + "，重试后）: " + e.getMessage());
                    return;
                }
                ffOk = nativeLibExists(ffDir, platform, "jniavutil");
                jcOk = nativeLibExists(jcDir, platform, "jnijavacpp");
            }
            // 诊断：最终关键库是否存在（解压清单已由 extractNatives 打印）
            LOGGER.info("[CinemaForYou] 原生库完整性: root={}, jniavutil={}, jnijavacpp={}",
                    root.toAbsolutePath(), ffOk, jcOk);
            if (!ffOk || !jcOk) {
                fail("解码组件文件缺失（平台 " + platform + "，jniavutil=" + ffOk
                        + "，jnijavacpp=" + jcOk + "，根目录 " + root.toAbsolutePath() + "）");
                return;
            }
            // 注册：无论缓存命中（目录已存在）还是本次下载，只要决定用该目录就必须注册，
            // 否则 JavaCPP 在首次引用 avutil 等类时会找不到 jniavutil。
            try {
                registerLibraryPaths(ffDir, jcDir);
            } catch (Throwable t) {
                fail("解码组件路径注册失败（平台 " + platform + "）: " + t);
                return;
            }
            synchronized (LOCK) {
                ready = true;
                lastFailure = null;
            }
            // 诊断：注册成功后打印平台、目录绝对路径、关键库是否存在及已生效的搜索路径
            LOGGER.info("[CinemaForYou] 解码原生库注册成功: platform={}, 平台目录={}, "
                            + "jniavutil={}({}), jnijavacpp={}({}), linkpath={}, preloadpath={}",
                    platform, root.toAbsolutePath(),
                    ffOk, libFileName(platform, "jniavutil"),
                    jcOk, libFileName(platform, "jnijavacpp"),
                    System.getProperty("org.bytedeco.javacpp.platform.linkpath"),
                    System.getProperty("org.bytedeco.javacpp.platform.preloadpath"));
            // ffmpeg 日志级别：必须在注册成功之后才可引用 avutil（其类初始化会加载
            // jniavutil）。原实现放在 VideoPlayer 构造函数里，会在注册前触发类初始化
            // 导致 UnsatisfiedLinkError 且该类永久不可用，现统一移到此处。
            try {
                org.bytedeco.ffmpeg.global.avutil.av_log_set_level(
                        org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR);
            } catch (Throwable t) {
                LOGGER.warn("[CinemaForYou] 设置 FFmpeg 日志级别失败（不影响播放）", t);
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

    /**
     * 设置 JavaCPP 库搜索路径属性（linkpath/preloadpath），并强制重载其平台属性缓存。
     *
     * <p>两个目录都必须包含在搜索路径里：{@code jniavutil} 等在 ffmpeg 目录，
     * {@code jnijavacpp} 在 javacpp 目录，缺任一个都会在类初始化时报
     * UnsatisfiedLinkError。
     *
     * <p>JavaCPP 的 {@link Loader#loadProperties()} 结果会全局缓存，若在注册前已有
     * 任何 JavaCPP 类被引用，缓存里将不含 linkpath/preloadpath；这里强制重载一次，
     * 保证即使发生上述情况属性也能生效。
     */
    private static void registerLibraryPaths(Path ffDir, Path jcDir) {
        String ff = ffDir.toAbsolutePath().toString();
        String jc = jcDir.toAbsolutePath().toString();
        String sep = java.io.File.pathSeparator;
        System.setProperty("org.bytedeco.javacpp.platform.linkpath", ff + sep + jc);
        System.setProperty("org.bytedeco.javacpp.platform.preloadpath", jc + sep + ff);
        // forceReload=true：丢弃可能不含上面路径的旧缓存（必须在首次引用 JavaCPP 类前）
        Loader.loadProperties(true);
    }

    /** 确保 natives 已下载解压（标记文件或必需原生库缺失时联网获取）。 */
    private static void prepare(String platform, Path root, Path ffBase, Path jcBase)
            throws Exception {
        String marker = "ok-" + FFMPEG_VERSION + "-" + JAVACPP_VERSION;
        Path markerFile = root.resolve("cinemaforyou-natives-" + platform + ".txt");
        boolean have = Files.isRegularFile(markerFile)
                && Files.readString(markerFile).trim().equals(marker)
                && nativeLibExists(jcBase, platform, "jnijavacpp")
                && nativeLibExists(ffBase, platform, "jniavutil");
        if (have) {
            // 缓存命中也要注册（由 ensureNow 统一执行），这里只记录以便诊断
            LOGGER.info("[CinemaForYou] 解码原生库缓存命中: {}（下一步注册搜索路径）",
                    root.toAbsolutePath());
            return;
        }

        LOGGER.info("[CinemaForYou] 首次使用：下载解码组件（{}，约 30MB，仅一次）...", platform);
        // 目录不完整：清空重建
        if (Files.exists(root)) {
            deleteRecursively(root);
        }
        Files.createDirectories(ffBase);
        Files.createDirectories(jcBase);

        String ffArtifact = "org/bytedeco/ffmpeg/" + FFMPEG_VERSION
                + "/ffmpeg-" + FFMPEG_VERSION + "-" + platform + ".jar";
        String jcArtifact = "org/bytedeco/javacpp/" + JAVACPP_VERSION
                + "/javacpp-" + JAVACPP_VERSION + "-" + platform + ".jar";
        Path tmp = Files.createTempDirectory("cfy-natives-");
        try {
            Path ffJar = tmp.resolve("ffmpeg.jar");
            Path jcJar = tmp.resolve("javacpp.jar");
            try {
                download(ffArtifact, ffJar);
                download(jcArtifact, jcJar);
            } catch (Exception e) {
                throw new IOException("下载失败: " + e.getMessage(), e);
            }
            try {
                // 不假设 jar 内目录结构：按 basename 匹配必需库，并解出同目录其它原生库
                extractNatives(ffJar, ffBase, platform, "jniavutil");
                extractNatives(jcJar, jcBase, platform, "jnijavacpp");
            } catch (IOException e) {
                throw new IOException("解压失败: " + e.getMessage(), e);
            }
            Files.writeString(markerFile, marker);
            LOGGER.info("[CinemaForYou] 解码组件下载解压完成: {}", root.toAbsolutePath());
        } finally {
            deleteRecursively(tmp);
        }
    }

    /**
     * 依次尝试各镜像下载。
     *
     * <p>每个镜像都记录 HTTP 状态码与下载字节数；非 200 或 0 字节视为失败并换下一
     * 个镜像。下载完成后校验文件头为 zip/jar（前两字节 PK），否则视为镜像错误页。
     * 全部镜像失败时，异常信息带上最后一次的 HTTP 码与 URL。
     */
    private static void download(String artifact, Path target) throws Exception {
        int lastCode = -1;
        String lastUrl = null;
        Exception lastErr = null;
        for (String mirror : MIRRORS) {
            String url = mirror + "/" + artifact;
            lastUrl = url;
            HttpURLConnection c = null;
            int code = -1;
            long bytes = 0;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(90_000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 CinemaForYou/1.0.7");
                code = c.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }
                try (InputStream in = c.getInputStream();
                     OutputStream out = Files.newOutputStream(target)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        bytes += n;
                    }
                }
                lastCode = code;
                if (bytes <= 0) {
                    throw new IOException("下载字节数为 0");
                }
                // 校验下载到的确实是 zip/jar（前两字节 PK），排除镜像返回的 HTML 错误页
                if (!hasZipMagic(target)) {
                    throw new IOException("文件头不是 zip/jar（PK）");
                }
                // SHA-1 为增强校验：.sha1 可取到时强制比对，取不到时放行（不阻断下载）
                verifySha1IfAvailable(url, target);
                LOGGER.info("[CinemaForYou] 镜像下载成功: HTTP {}, {} 字节, URL {}",
                        code, bytes, url);
                return;
            } catch (Exception e) {
                lastErr = e;
                if (code > 0) {
                    lastCode = code;
                }
                LOGGER.warn("[CinemaForYou] 镜像下载失败: HTTP {}, {} 字节, URL {}, 原因 {}",
                        code, bytes, url, e.toString());
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }
        throw new IOException("全部镜像下载失败（最后 HTTP " + lastCode + "，URL " + lastUrl
                + "，原因 " + lastErr + "）");
    }

    /** 文件头是否为 zip/jar 的 PK 魔数。 */
    private static boolean hasZipMagic(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            int b0 = in.read();
            int b1 = in.read();
            return b0 == 'P' && b1 == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /** 尽力做 SHA-1 校验：.sha1 不可用时跳过（不因辅助校验阻断可用下载）。 */
    private static void verifySha1IfAvailable(String url, Path target) throws IOException {
        String expectHex;
        try {
            byte[] expect = readUrlBytes(url + ".sha1", 15_000);
            expectHex = new String(expect, StandardCharsets.UTF_8)
                    .trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            LOGGER.info("[CinemaForYou] 跳过 SHA-1 校验（.sha1 不可用）: {}", e.toString());
            return;
        }
        try {
            String actualHex = sha1(target);
            if (!expectHex.equals(actualHex)) {
                throw new IOException("SHA-1 校验失败（期望 " + expectHex + "，实际 " + actualHex + "）");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("[CinemaForYou] SHA-1 计算失败，跳过校验: {}", e.toString());
        }
    }

    private static byte[] readUrlBytes(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 CinemaForYou/1.0.7");
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

    /**
     * 解压 jar 内的原生库到目标目录。
     *
     * <p>不假设 jar 内目录结构：遍历全部条目，凡 basename 等于该平台必需库名的条目
     * 直接写出到目标目录（保留原文件名）；同时把与必需库位于同一 jar 目录下的其它
     * 原生库（.so / .so.N / .dll / .dylib）一并解出——FFmpeg 各共享库互相依赖，
     * JavaCPP 靠 preload 机制加载，缺一不可。
     */
    private static void extractNatives(Path jar, Path targetDir, String platform,
                                       String requiredBase) throws IOException {
        String required = libFileName(platform, requiredBase);
        Files.createDirectories(targetDir);
        int extracted = 0;
        ArrayList<String> head = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            // 先定位必需库在 jar 内的目录
            String requiredDir = null;
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                if (basename(e.getName()).equals(required)) {
                    requiredDir = dirPart(e.getName());
                    break;
                }
            }
            // 再解出必需库及同目录下的其它原生库
            entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String name = e.getName();
                String base = basename(name);
                boolean wanted = base.equals(required)
                        || (requiredDir != null && dirPart(name).equals(requiredDir)
                        && isNativeLibName(base, platform));
                if (!wanted) continue;
                Path out = targetDir.resolve(base).normalize();
                if (!out.startsWith(targetDir)) continue; // 防路径穿越
                try (InputStream in = zf.getInputStream(e);
                     OutputStream os = Files.newOutputStream(out)) {
                    in.transferTo(os);
                }
                extracted++;
                if (head.size() < 10) {
                    head.add(base);
                }
            }
        }
        // 诊断：打印实际解出的文件清单（最多前 10 个 + 总数）
        LOGGER.info("[CinemaForYou] 解压 {} -> {}: 共 {} 个原生库，前 {} 个: {}",
                jar.getFileName(), targetDir, extracted, head.size(), String.join(", ", head));
    }

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static String dirPart(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(0, i) : "";
    }

    /** 是否为目标平台的原生库文件名（Windows/.dll、macOS/.dylib、Linux+Android/.so[.N]）。 */
    private static boolean isNativeLibName(String base, String platform) {
        String lower = base.toLowerCase(Locale.ROOT);
        if (platform.startsWith("windows")) return lower.endsWith(".dll");
        if (platform.startsWith("macosx")) return lower.endsWith(".dylib");
        // Linux/Android：libX.so 或带版本号的 libX.so.59
        return lower.endsWith(".so") || lower.contains(".so.");
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

    /**
     * 原生库根目录。
     *
     * <p>桌面平台沿用游戏目录下的 {@code cinema/natives/<platform>}。
     *
     * <p>安卓必须使用应用内部可执行目录：游戏目录常在 {@code /storage/emulated/0}
     * 等外部存储上，而这些分区通常以 noexec 挂载，即使 .so 写入成功，
     * {@code System.load}/dlopen 也会因无法 mmap 可执行页而失败。取值优先级：
     * {@code java.io.tmpdir}（PojavLauncher/FCL 指向应用内部 cache）→
     * {@code user.home}；最终仍保留"平台目录名"结构。
     */
    private static Path nativesRoot(String platform) {
        boolean android = platform.startsWith("android") || isAndroidRuntime();
        Path base;
        if (android) {
            String tmp = System.getProperty("java.io.tmpdir", "").trim();
            String home = System.getProperty("user.home", "").trim();
            Path execBase;
            if (!tmp.isEmpty()) {
                execBase = Path.of(tmp);
            } else if (!home.isEmpty()) {
                execBase = Path.of(home);
            } else {
                // 极端兜底：仍用游戏目录（可能 noexec，但好过直接崩溃）
                execBase = Minecraft.getInstance().gameDirectory.toPath();
                LOGGER.warn("[CinemaForYou] 无法取得可执行目录（tmpdir/user.home 均为空），"
                        + "回退游戏目录");
            }
            base = execBase.resolve("cinemaforyou-natives");
        } else {
            Minecraft mc = Minecraft.getInstance();
            base = mc.gameDirectory.toPath().resolve("cinema").resolve("natives");
        }
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

    /** 按平台返回原生库文件名（Windows: name.dll；macOS: libname.dylib；Linux/Android: libname.so）。 */
    private static String libFileName(String platform, String base) {
        if (platform.startsWith("windows")) return base + ".dll";
        if (platform.startsWith("macosx")) return "lib" + base + ".dylib";
        return "lib" + base + ".so";
    }

    /** 目录下是否存在指定基名的原生库（按平台后缀优先，其余后缀兜底识别）。 */
    private static boolean nativeLibExists(Path dir, String platform, String base) {
        if (Files.isRegularFile(dir.resolve(libFileName(platform, base)))) return true;
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
