package com.cinemaforyou.client.video;

import com.cinemaforyou.client.audio.AudioPlayer;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.network.QueueClient;
import com.cinemaforyou.client.render.VideoFrameTexture;
import com.cinemaforyou.client.util.DebugLog;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.network.QueueEntry;
import com.cinemaforyou.network.ScreenActionPayload;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端视频播放器：每个屏幕一个实例。
 *
 * <p>线程模型（同步重构版）：
 * <ul>
 *   <li>解码线程：{@code grabImage} → BGR24 手动转 ABGR → 生成 mip 链 →
 *       写入空闲帧槽；解码节奏由"帧槽队列 + 主时钟"控制。</li>
 *   <li>渲染线程：每个渲染帧调用 {@link #tick()}，只把<strong>已到呈现时间</strong>
 *       的最新帧上传到 {@link VideoFrameTexture}（含各 mip 层，三线性采样）。
 *       画面更新频率 = 游戏渲染帧率；mip 链消除远处/斜视时 NEAREST 点采样
 *       造成的黑色噪点与爬行闪烁。</li>
 * </ul>
 *
 * <p>音视频同步：{@link AudioPlayer} 作为主时钟（声音真正播出的媒体位置），
 * 视频帧按其时间戳（PTS）换算到同一媒体时间轴，到点才允许被 tick 上传；
 * 音频未就绪或不存在时自动回退到墙钟。音频 EOF 不再回绕重播，视频 EOF
 * 会等音频播完（含排空）再停，避免掐尾音。
 *
 * <p>帧槽状态机（3 槽）：0=空、1=解码中、2=待呈现、3=上传中。解码线程
 * 从不改写上传中/待呈现的槽，消除旧"双缓冲无握手"造成的撕裂与丢帧。
 *
 * <p>注意：{@link NativeImage#getPixelsABGR()} 返回的是<strong>拷贝</strong>（Yarn 名
 * {@code copyPixelsAbgr}），写回无效；必须经 {@link NativeImage#getPointer()}
 * 写 native 内存。
 */
@Environment(EnvType.CLIENT)
public class VideoPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CinemaForYou/VideoPlayer");

    /**
     * 帧槽上限/内存预算：解码可领先帧数按"分辨率+内存预算"自适应
     * （1080p 帧约 12MB，预算 400MB ≈ 1.2s@24fps；480p ≈ 5s）。
     * 槽越多抗网络抖动越强；解码帧缓冲受内存限制（浏览器缓存的是压缩数据）。
     */
    private static final int MAX_SLOTS = 72;
    private static final int SLOT_MEMORY_MB = 400;
    /** 目标领先时长（秒）与自适应下限。 */
    private static final double BUFFER_TARGET_SECONDS = 3.0;
    private int slotCount = 10; // 首帧后按实际分辨率/帧率重算
    /** 槽状态。 */
    private static final int SLOT_EMPTY = 0;
    private static final int SLOT_DECODING = 1;
    private static final int SLOT_FILLED = 2;
    private static final int SLOT_UPLOADING = 3;

    /**
     * 呈现提前量（毫秒）。音频位置按"已从声卡缓冲读出"估计，略滞后于耳朵；
     * 允许视频最多提前这么一点显示，避免听感上"声音总是慢半拍"。
     */
    private static final long PRESENTATION_LEAD_MS = 100L;
    /** EOF 后等待音频收尾的最长时间，防止个别长尾音频让画面永远定格。 */
    private static final long EOF_MAX_WAIT_MS = 12_000L;
    /** 小于该值的目标位置不用 setTimestamp seek，改用"重开 grabber"：
     *  实测部分 WebM（VP9+alpha、带音轨）seek 到 0/极小目标会错误落到文件末尾的
     *  关键帧（例如 15s 的视频落到 13360ms，复现稳定），而重新打开一定从 0 开始。
     *  15s 内的短片重开只要十几毫秒，代价可忽略。 */
    private static final long DIRECT_SEEK_MIN_MS = 500L;

    private final UUID screenId;
    private volatile CinemaScreen screen;
    private final String sourceUrl;
    private volatile long durationMs = 0L;
    /**
     * 当前内容是否来自播放申请授权（服务端屏幕状态同步）：
     * true 时播完不自动循环/不自动连播——一次"接受"只授权一次播放。
     */
    private volatile boolean fromRequest = false;

    private FFmpegFrameGrabber grabber; // 注意：解码线程写、主线程可能在 release() 读，见 release()
    private volatile AudioPlayer audioPlayer;
    private Thread decodeThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    /** release() 是否已发起（幂等保护，handleStateChange/handleSync 可能重复调用）。 */
    private volatile boolean releaseStarted = false;
    /** release() 发起时刻（毫秒），上层对超时未排空的旧播放器做兜底丢弃。 */
    private volatile long releasedAtMs = 0L;

    // ───────────── 帧槽（解码线程写、渲染线程读，frameLock 保护） ─────────────
    private final Object frameLock = new Object();
    // 每个槽 = 一条 mip 链：slotPixels[槽][0] 为原始帧，[1..] 为逐级减半的 mip 层
    private int[][][] slotPixels = null;
    private final long[] slotDueMs = new long[MAX_SLOTS];
    private final int[] slotState = new int[MAX_SLOTS];
    private volatile int frameWidth;
    private volatile int frameHeight;

    // ───────────── 跨线程时钟与状态（decode 线程写，渲染线程只读 volatile） ─────────────
    /** 当前主时钟位置（毫秒，媒体时间轴）：音频可听位置或墙钟回退值。 */
    private volatile long masterPosMs = 0L;
    /** 视频流结束时刻（-1 = 未结束），用于等待音频收尾后停播。 */
    private volatile long videoEofAtMs = -1L;
    private volatile boolean endReported = false;
    private volatile String error = null;
    private volatile boolean errorReported = false;
    private volatile boolean pendingRelease = false;
    private volatile long pendingSeekMs = -1;

    private VideoFrameTexture texture;
    private Identifier textureId;

    // ───────────── 冻结看门狗 / 重开解码流（连续快退卡死防护） ─────────────
    /** 解码流直链（resolve 结果），重开解码流时复用。 */
    private String videoResolvedUrl;
    /** 当前流是否支持精确定位（HLS 等不可 seek 的流为 false）。 */
    private volatile boolean seekCapable = true;
    /** 解码线程置位：请求重开解码流（seek 失败/长时间滚不到目标时）。 */
    private volatile boolean reopenRequested = false;
    /** 最近一次成功出新帧的时刻（解码线程写、渲染线程读，毫秒）。 */
    private volatile long lastFrameAtMs = 0L;
    /** 已检测为透明视频（含 alpha<250 像素），渲染用混合管线。 */
    private volatile boolean translucentContent = false;
    private int alphaCheckFrames = 0;
    /** WebM（VP8/VP9）透明通道探测：容器标记 alpha_mode=1 时必须换 libvpx
     *  解码器，FFmpeg 原生 vp8/vp9 解码器会直接丢弃 alpha。每路视频只探测一次。 */
    private boolean alphaDecoderProbed = false;
    /** 探测命中的 libvpx 解码器名（"libvpx-vp9"/"libvpx"），null=用默认解码器。 */
    private String alphaDecoderName;
    /** 循环/起播时间线埋点：定位"延迟几秒"到底花在哪个阶段。
     *  t0 = start()/restart() 时刻；首帧解码、首帧上屏分别打点，上屏时汇总输出一次。 */
    private volatile long timelineT0Ms = 0L;
    private volatile long timelineFirstDecodeMs = 0L;
    private volatile boolean timelineLogged = false;
    /** 音频门控累计等待（毫秒），时间线归因用。 */
    private volatile long audioGateWaitedMs = 0L;
    /** 循环次数（restart() 递增），日志里区分第几遍。 */
    private int loopIndex = 0;
    /** 上一代被释放的播放器：新一代打开原生 grabber 前必须等它把 grabber 释放完，
     *  否则两代 grabber（尤其 H.264 原生 ↔ VP9/libvpx 软解）原生状态交叉，
     *  会出现新播放器打不开/不吐帧（透明与不透明视频来回切换卡死的根因）。 */
    private static final java.util.concurrent.atomic.AtomicReference<VideoPlayer> LAST_RELEASED =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** 本实例的原生 grabber 是否已释放完毕（解码线程 finally 里 countDown）。 */
    private final java.util.concurrent.CountDownLatch nativeClosed =
            new java.util.concurrent.CountDownLatch(1);
    /** alpha 探测结果静态缓存（按 resolvedUrl）：值为解码器名（命中）或空串（已探测无 alpha）。
     *  循环播放重建 VideoPlayer 时复用，避免每次都双开 grabber 探测——
     *  这是本地透明 WebM 循环延迟和反复打开 libvpx 累积卡死的关键修复点。 */
    private static final java.util.Map<String, String> ALPHA_DECODER_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 渲染线程看门狗：已触发过补 seek（未恢复则升级为重开）。 */
    private volatile boolean resyncArmed = false;
    private volatile long resyncArmedAtMs = 0L;
    /** 看门狗强制重开的最小间隔（防重开风暴）。 */
    private volatile long lastForcedActionAtMs = 0L;
    /** 音频门控：会话创建后等它真正出声的最长时间；超时画面先行（音频掉线时防永久黑屏）。 */
    private static final long AUDIO_GATE_MAX_MS = 3500L;
    /** 本地源音频门控上限：本地文件音频启动不该有几秒延迟，800ms 足够；
     *  超过即判定音频异常/无音频流，画面先行，避免本地视频循环重播被卡三四秒。 */
    private static final long AUDIO_GATE_LOCAL_MAX_MS = 800L;
    /** 音频门控开始等待的时刻（0 = 未在等）。 */
    private volatile long audioGateStartMs = 0L;
    private volatile boolean audioGateLogged = false;
    /** 最近一次成功 seek/重开的时刻（毫秒）：之后短暂出现的空帧不算 EOF。 */
    private volatile long lastSeekHandledAtMs = 0L;
    /** 连续空帧计数（seek 后与正常播放共用，见 decodeLoop）。 */
    private int consecutiveNulls = 0;
    /** 上次 seek 目标超出片尾：按"正常播完"处理，由解码循环走既有 EOF 链路。 */
    private boolean seekPastEnd = false;
    /** seek 落点超前时的后退重试次数。 */
    private int seekFixTries = 0;
    /** 最近一次成功上传显示帧的时刻（渲染线程写，冻结看门狗用）。 */
    private volatile long lastUploadAtMs = 0L;
    /** 本片段首帧是否尚未上屏：start/restart/applySeek/reopenGrabber 置位，
     *  渲染线程首帧上传成功后清零。为真时主时钟冻结在 segmentStartMs，
     *  使"解析链接→打开 grabber→解码首帧"的启动耗时不计入播放位置，
     *  避免首帧上屏前时钟已前进数秒、画面被连续快放追赶。 */
    private volatile boolean awaitingFirstUpload = true;

    // decode 线程私有状态（无需 volatile）
    private long firstPtsUs = -1L;       // 片段首帧 PTS（µs），仅作音频启动/日志标记
    private long lastPtsUs = -1L;        // 上一帧 PTS，用于单调化
    private boolean wallFallback;        // true = 当前用墙钟而不是音频时钟
    private long wallBasePosMs;          // 墙钟回退的起点媒体位置
    private long wallBaseWallMs;         // 墙钟回退的起点墙钟
    private long segmentStartMs;         // 当前片段起始媒体位置（seek 后重置）

    /** 每个 VideoPlayer 实例的唯一序号，用于生成唯一 textureId。
     *  防止同一屏幕的新旧播放器共用 textureId 时，旧播放器释放纹理把新播放器的也关掉。 */
    private static final java.util.concurrent.atomic.AtomicLong INSTANCE_SEQ =
            new java.util.concurrent.atomic.AtomicLong(0);
    private final long instanceSeq = INSTANCE_SEQ.incrementAndGet();

    public VideoPlayer(UUID screenId, CinemaScreen screen, String sourceUrl) {
        this.screenId = screenId;
        this.screen = screen;
        this.sourceUrl = sourceUrl;
        // 此处绝不能引用 avutil/FFmpeg 等 JavaCPP 类：那会触发 jniavutil 类初始化，
        // 而此时 natives 可能尚未注册（首次启动需下载），一旦失败该类将永久不可用。
        // ffmpeg 日志级别已移到 NativeRuntime 注册成功后设置；这里仅非阻塞推动后台准备，
        // 真正使用原生库前由解码线程的 NativeRuntime.ensureBlocking() 保证已注册。
        NativeRuntime.startBackground();
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    /**
     * 标记当前内容是否来自播放申请授权（服务端屏幕状态同步，见
     * {@code ScreenStatePayload#fromRequest}）。
     */
    public void setFromRequest(boolean value) {
        this.fromRequest = value;
    }

    /** 是否本地源（非 http/https）：本地文件音频启动不该有几秒延迟，
     *  用于音频门控选短时长，避免本地视频循环重播被卡三四秒。 */
    private boolean isLocalSource() {
        String s = sourceUrl;
        return s != null && !s.startsWith("http://") && !s.startsWith("https://");
    }

    /** 该视频是否检测到透明内容（渲染用混合管线，避免黑底）。 */
    public boolean isTranslucentContent() {
        return translucentContent;
    }

    /** 解码线程是否存活（意外退出时由上层重建播放器）。 */
    public boolean isDecoderAlive() {
        Thread t = decodeThread;
        return t != null && t.isAlive();
    }

    public void updateScreen(CinemaScreen newScreen) {
        this.screen = newScreen;
        if (audioPlayer != null) {
            audioPlayer.updateScreen(newScreen);
        }
    }

    /** 返回当前视频帧纹理标识（无纹理时返回 null）。 */
    public Identifier getTextureId() {
        return textureId;
    }

    /** 视频总时长（毫秒，未知时为 0）。 */
    public long getDurationMs() {
        return durationMs;
    }

    // ───────────── 生命周期 ─────────────

    public void start(long startPosMs) {
        if (running.get()) return;
        running.set(true);
        paused.set(false);
        endReported = false;
        videoEofAtMs = -1L;
        wallFallback = true;
        wallBasePosMs = Math.max(0L, startPosMs);
        wallBaseWallMs = System.currentTimeMillis();
        segmentStartMs = wallBasePosMs;
        awaitingFirstUpload = true;   // 首帧上屏前冻结主时钟，启动耗时不进入播放位置
        timelineT0Ms = wallBaseWallMs;
        timelineFirstDecodeMs = 0L;
        timelineLogged = false;
        audioGateWaitedMs = 0L;
        decodeThread = new Thread(() -> decodeLoop(startPosMs), "CinemaForYou-Decoder-" + screenId);
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    public void pause() {
        paused.set(true);
        if (audioPlayer != null) {
            audioPlayer.pause();
        }
    }

    public void resume() {
        paused.set(false);
        if (audioPlayer != null) {
            audioPlayer.resume();
        }
    }

    /** 请求 seek 到指定位置（毫秒）。解码线程在下一帧前执行。 */
    public void seek(long positionMs) {
        pendingSeekMs = Math.max(0L, positionMs);
        if (audioPlayer != null) {
            audioPlayer.seek(positionMs);
        }
    }

    /** 返回当前播放位置（毫秒），主时钟（音频可听位置优先）。 */
    public long getPositionMs() {
        return masterPosMs;
    }

    public void release() {
        if (releaseStarted) return; // 幂等：handleStateChange/handleSync 可能重复调用
        releaseStarted = true;
        releasedAtMs = System.currentTimeMillis();
        running.set(false);
        if (decodeThread != null) {
            decodeThread.interrupt();
            // 只短暂等待：真正的"旧 grabber 先释放、新 grabber 后打开"顺序由
            // LAST_RELEASED + nativeClosed 在新播放器的解码线程上保证（见 awaitPreviousNativeClose），
            // 这里若长等会卡住调用线程（网络包处理/渲染线程），表现为切视频时整体卡顿。
            long joinMs = 100L;
            try { decodeThread.join(joinMs); } catch (InterruptedException ignored) {}
        }
        LAST_RELEASED.set(this);
        // 解码线程若已退出，grabber 已由它自己的 finally 释放（latch 已 countDown）；
        // 若仍存活，则由它退出时释放并 countDown。这里只兜底处理"从未启动过线程"的情况。
        if (decodeThread == null) {
            nativeClosed.countDown();
        }
        // 等解码线程退出后再收尾，避免其刚创建的音频会话成为孤儿
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
        // 解码线程若仍阻塞在网络读取中（join 超时），绝不在此跨线程 release 原生
        // grabber——交给解码线程自己的 finally 释放，避免 native 层崩溃/死锁
        FFmpegFrameGrabber g = grabber;
        boolean decodeAlive = decodeThread != null && decodeThread.isAlive();
        if (g != null && !decodeAlive) {
            try { g.release(); } catch (Exception ignored) {}
            grabber = null;
        }
        pendingRelease = true;
    }

    /** release() 发起时刻（毫秒），上层据此对超时未排空的旧播放器做兜底丢弃。 */
    public long getReleasedAtMs() {
        return releasedAtMs;
    }

    private void releaseTexture() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            // 清理 ScreenQuad 中该纹理对应的 RenderType 缓存（每个实例唯一 textureId，
            // 不复用，需同步释放避免缓存无限增长）
            com.cinemaforyou.client.render.ScreenQuad.releaseVideoRenderType(textureId);
            textureId = null;
        }
    }

    /**
     * 循环重播：复用当前 VideoPlayer 实例，重置播放状态后让解码线程重开 grabber 到 0。
     *
     * <p>相比 release() + new VideoPlayer() 的重建方式，复用实例能：
     * <ul>
     *   <li>保证旧 grabber 在同一解码线程内同步 stop+release 后再开新的，
     *       避免跨线程 release 的 join(200) 抢不出 native grab 导致的资源泄漏累积
     *       （循环 2-3 次后 libvpx 打不开、画面卡死的根因）；</li>
     *   <li>不反复创建/销毁 javacv 对象，减少 native 引用计数抖动。</li>
     * </ul>
     *
     * <p>解码线程已退出时回退为 start(0) 重新启动。
     */
    public void restart() {
        // 重置 EOF / 结束报告状态
        videoEofAtMs = -1L;
        endReported = false;
        error = null;
        errorReported = false;
        // 重置音频会话（循环重播要从头出声）
        if (audioPlayer != null) {
            audioPlayer.stop();
            audioPlayer = null;
        }
        // 重置时钟到起点
        wallFallback = true;
        wallBasePosMs = 0L;
        wallBaseWallMs = System.currentTimeMillis();
        segmentStartMs = 0L;
        masterPosMs = 0L;
        awaitingFirstUpload = true;   // 首帧上屏前冻结主时钟，重开耗时不进入播放位置
        // 重置帧上传跟踪与看门狗
        lastUploadAtMs = 0L;
        resyncArmed = false;
        resyncArmedAtMs = 0L;
        audioGateStartMs = 0L;
        audioGateLogged = false;
        // 时间线埋点：记录本轮循环的起点，首帧上屏时输出各阶段耗时
        loopIndex++;
        timelineT0Ms = System.currentTimeMillis();
        timelineFirstDecodeMs = 0L;
        timelineLogged = false;
        audioGateWaitedMs = 0L;
        // 重置解码线程私有状态（解码线程会读这些字段）
        firstPtsUs = -1L;
        lastPtsUs = -1L;
        consecutiveNulls = 0;
        seekFixTries = 0;
        // 注意：不重置 translucentContent——同一 URL 重播，透明属性不会变。
        // 重置后头几帧 alpha 扫描未完成期间渲染管线会短暂用不透明模式，
        // 带 alpha 的帧被直接绘制，绿幕会闪现一两帧。保留标志从头就走混合管线。
        if (isDecoderAlive()) {
            // 复用解码线程：请求重开 grabber 到片段起点（0）
            reopenRequested = true;
            LOGGER.info("[CinemaForYou] 屏幕 {} 循环重播：复用解码线程重开 grabber", screenId);
        } else {
            // 解码线程已退出（异常/老死）：重新启动
            running.set(true);
            paused.set(false);
            decodeThread = new Thread(() -> decodeLoop(0L), "CinemaForYou-Decoder-" + screenId);
            decodeThread.setDaemon(true);
            decodeThread.start();
            LOGGER.info("[CinemaForYou] 屏幕 {} 循环重播：解码线程已退出，重新启动", screenId);
        }
    }

    // ───────────── 解码循环 ─────────────

    private void decodeLoop(long startPosMs) {
        LOGGER.info("[CinemaForYou] 解码线程启动: {} src={}", screenId, sourceUrl);
        // #region debug-point B:video-start
        debugPoint("B", "VideoPlayer.decodeLoop:start",
                "[DEBUG] video decode loop start",
                "screenId", screenId,
                "sourceUrl", trimForLog(sourceUrl),
                "resolutionHeight", screen != null ? screen.resolutionHeight() : 0,
                "displayScale", screen != null ? screen.displayScalePercent() : 0);
        // #endregion
        try {
            UrlResolver.ResolvedSource resolved = UrlResolver.resolve(sourceUrl);
            if (resolved == null) {
                String why = UrlResolver.getLastError();
                error = "无法解析视频源" + (why != null ? "： " + why : "");
                LOGGER.error("[CinemaForYou] {}", error);
                return;
            }

            videoResolvedUrl = resolved.videoUrl();
            // 直链防盗链：带 Referer/UA 打不开时（部分 CDN 反而拒绝带来源头），
            // 自动去掉自定义请求头重试一次
            boolean httpDirect = videoResolvedUrl != null
                    && (videoResolvedUrl.startsWith("http://")
                        || videoResolvedUrl.startsWith("https://"));
            try {
                awaitPreviousNativeClose();
                grabber = openConfiguredGrabber(videoResolvedUrl);
                grabber.start();
                grabber = switchToVpxAlphaDecoder(grabber, videoResolvedUrl, true);
            } catch (Exception firstOpen) {
                if (httpDirect && UrlResolver.ffmpegHttpHeaders(sourceUrl, videoResolvedUrl) != null) {
                    LOGGER.warn("[CinemaForYou] 带头部打开失败({})，尝试无自定义请求头重试: {}",
                            String.valueOf(firstOpen.getMessage()), trimForLog(videoResolvedUrl));
                    try {
                        if (grabber != null) {
                            try { grabber.release(); } catch (Exception ignored) {}
                        }
                        awaitPreviousNativeClose();
                        grabber = openConfiguredGrabber(videoResolvedUrl, false);
                        grabber.start();
                        grabber = switchToVpxAlphaDecoder(grabber, videoResolvedUrl, false);
                    } catch (Exception e2) {
                        throw e2; // 二次也失败：抛原异常路径
                    }
                } else {
                    throw firstOpen;
                }
            }
            long startMs = System.currentTimeMillis();
            durationMs = Math.max(0L, grabber.getLengthInTime() / 1000L);
            LOGGER.info("[CinemaForYou] grabber 启动成功 {}x{} @ {}fps, 耗时 {}ms",
                    grabber.getImageWidth(), grabber.getImageHeight(),
                    grabber.getFrameRate(), System.currentTimeMillis() - startMs);
            // #region debug-point B:video-grabber-started
            debugPoint("B", "VideoPlayer.decodeLoop:grabber-started",
                    "[DEBUG] video grabber started",
                    "resolvedUrl", trimForLog(resolved.videoUrl()),
                    "imageWidth", grabber.getImageWidth(),
                    "imageHeight", grabber.getImageHeight(),
                    "fps", grabber.getFrameRate(),
                    "durationMs", durationMs,
                    "startupMs", System.currentTimeMillis() - startMs,
                    "hasVideo", grabber.hasVideo(),
                    "hasAudio", grabber.hasAudio());
            // #endregion

            if (startPosMs > 0) {
                long startTarget = startPosMs;
                if (durationMs > 0 && startTarget >= durationMs) {
                    LOGGER.warn("[CinemaForYou] 起始位置 {}ms 已超过时长 {}ms，按从头播放处理",
                            startTarget, durationMs);
                    startTarget = 0L;
                }
                if (startTarget < DIRECT_SEEK_MIN_MS) {
                    // 小目标（含"从头播放"）：刚打开的 grabber 本来就在 0，直接跳过 seek。
                    // 这一档位置 setTimestamp 在部分 WebM 上会错误跳到末尾（实测复现）。
                    LOGGER.info("[CinemaForYou] 起始位置 {}ms 很小，直接从 0 开始（跳过 seek）", startTarget);
                } else if (!safeSeek(startTarget)) {
                    // 流不支持 seek（HLS 等）：标记后直接从流起点播放，
                    // 且不再启用"落点超前"回溯逻辑（那套是给精确 seek 的 mp4 用的）
                    seekCapable = false;
                    LOGGER.warn("[CinemaForYou] 起始 seek 失败，从头/当前位置播放");
                }
                lastSeekHandledAtMs = System.currentTimeMillis();
            }

            if (!grabber.hasVideo()) {
                // 纯音频源（mp3/音频流等）：黑屏播放音频直到结束
                LOGGER.info("[CinemaForYou] 源不含视频流，仅播放音频: {}", trimForLog(sourceUrl));
                startAudioIfNeeded(resolved);
                markVideoEof();
                waitAudioFinished();
                return;
            }

            while (running.get()) {
                if (reopenRequested) {
                    // seek 连续失败/滚帧卡死/看门狗升级：整体重开解码流再定位
                    reopenRequested = false;
                    reopenGrabber();
                    continue;
                }
                if (paused.get()) {
                    // 暂停时也处理挂起的 seek（控制界面"暂停中跳转"的场景）
                    if (pendingSeekMs >= 0) {
                        applySeek(pendingSeekMs);
                        if (seekPastEndReached(resolved)) return;
                    }
                    updateMasterClock(true);
                    Thread.sleep(25);
                    continue;
                }
                if (pendingSeekMs >= 0) {
                    // 合并窗口：快速连点时中间目标作废，只执行最后一次 seek
                    Thread.sleep(25);
                    if (pendingSeekMs >= 0) {
                        applySeek(pendingSeekMs);
                        if (seekPastEndReached(resolved)) return;
                    }
                    continue;
                }

                // 队列已满（都是尚未到呈现时间的未来帧）：等槽空出来再解码，
                // 天然把解码节奏钳制在播放速率附近，CPU 不空转
                waitForEmptySlot();
                if (!running.get()) break;

                updateMasterClock(false);

                // 仅取视频帧，跳过音频包（grab() 会夹杂 image==null 的音频帧）
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    long nowNull = System.currentTimeMillis();
                    if (nowNull - lastSeekHandledAtMs < 2000L) {
                        // 刚 seek/重开过：解码器可能瞬时返回空帧（快速连点快进/快退的
                        // 卡死根源就是这里被误判为"视频结束"）。短暂重试，
                        // 连续 5 次仍空则重开解码流而不是结束。
                        if (++consecutiveNulls >= 5) {
                            LOGGER.warn("[CinemaForYou] seek 后连续空帧 {} 次，重开解码流",
                                    consecutiveNulls);
                            consecutiveNulls = 0;
                            reopenRequested = true;
                            continue;
                        }
                        updateMasterClock(false);
                        Thread.sleep(25);
                        continue;
                    }
                    if (++consecutiveNulls < 2) {
                        // 正常播放中首次空帧：间隔 30ms 再确认一次，防误判
                        updateMasterClock(false);
                        Thread.sleep(30);
                        continue;
                    }
                    // 区分"真结束"与"网络停顿"：接近片尾（时长已知且位置到尾部）
                    // 才算 EOF；离片尾还远却连续空帧 = 网络/解码停顿，
                    // 不能结束播放（否则慢网下看久一点就会被误判结束、必须重播）。
                    consecutiveNulls = 0;
                    boolean nearEnd = durationMs > 0
                            && masterPosMs >= durationMs - 1500L;
                    // 无时长元数据的流（部分直播流）：音频已播完 + 连续空帧 = 真结束
                    if (!nearEnd && durationMs <= 0) {
                        AudioPlayer apEnd = audioPlayer;
                        if (apEnd != null && apEnd.isFinished()) {
                            nearEnd = true;
                        }
                    }
                    long stalledWall = nowNull - lastFrameAtMs;
                    if (!nearEnd) {
                        if (stalledWall < 30_000) {
                            LOGGER.warn("[CinemaForYou] 画面停顿 {}ms（位置 {}ms/时长 {}ms），"
                                    + "等待网络/解码恢复，不结束播放",
                                    stalledWall, masterPosMs, durationMs);
                            updateMasterClock(false);
                            Thread.sleep(400);
                            continue;
                        }
                        // 停顿超过 30s：整体重开解码流尝试恢复（有 3s 冷却防风暴）
                        if (nowNull - lastForcedActionAtMs > 3000L) {
                            lastForcedActionAtMs = nowNull;
                            reopenRequested = true;
                            LOGGER.warn("[CinemaForYou] 画面停顿 {}ms，重开解码流尝试恢复",
                                    stalledWall);
                        }
                        updateMasterClock(false);
                        Thread.sleep(400);
                        continue;
                    }
                    LOGGER.info("[CinemaForYou] 视频流播放到结尾: pos={}ms", masterPosMs);
                    // #region debug-point D:video-null-frame
                    debugPoint("D", "VideoPlayer.decodeLoop:eof",
                            "[DEBUG] video frame stream ended",
                            "screenId", screenId,
                            "masterPosMs", masterPosMs,
                            "decodedRelMs", lastPtsUs < 0 ? -1 : (lastPtsUs - Math.max(0, firstPtsUs)) / 1000);
                    // #endregion
                    startAudioIfNeeded(resolved);
                    markVideoEof();
                    waitAudioFinished();
                    return;
                }
                // 有帧返回：清空空帧计数（流是活的）
                consecutiveNulls = 0;
                if (frame.image == null) {
                    continue;
                }

                long ptsUs = grabber.getTimestamp();
                if (ptsUs < 0) ptsUs = 0;
                if (lastPtsUs >= 0 && ptsUs < lastPtsUs) {
                    ptsUs = lastPtsUs; // 个别容器 seek 后 PTS 轻微回退，做单调化
                }
                lastPtsUs = ptsUs;
                if (firstPtsUs < 0) {
                    firstPtsUs = ptsUs;
                    // seek 落点超前修正：若首帧画面时间明显晚于音频目标（个别文件
                    // 后退 seek 会落到目标之后的关键帧），画面会冻结等音频追上来。
                    // 这里主动向更早位置再 seek（最多 4 档递减），让画面从目标处开始。
                    long firstMs = firstPtsUs / 1000L;
                    if (seekCapable && firstMs > segmentStartMs + 400L && seekFixTries < 6) {
                        long[] backOffsets = {-800L, -2000L, -5000L, -12000L, -30000L, -60000L};
                        long backTarget = Math.max(0L,
                                segmentStartMs + backOffsets[Math.min(seekFixTries, backOffsets.length - 1)]);
                        seekFixTries++;
                        LOGGER.warn("[CinemaForYou] seek 落点超前：画面 {}ms 音频 {}ms，"
                                        + "向后退到 {}ms 重试",
                                firstMs, segmentStartMs, backTarget);
                        if (backTarget < DIRECT_SEEK_MIN_MS) {
                            // 目标太小：setTimestamp 在部分 WebM 上会错误落到末尾（实测复现），
                            // 改为整体重开——新 grabber 从 0 开始，等价于回到开头
                            LOGGER.warn("[CinemaForYou] seek 落点超前且目标 {}ms 过小，改为重开解码流",
                                    backTarget);
                            reopenRequested = true;
                            consecutiveNulls = 0;
                            continue;
                        }
                        if (seekGrabber(grabber, backTarget)) {
                            lastSeekHandledAtMs = System.currentTimeMillis();
                            consecutiveNulls = 0;
                            resetSlots();
                            firstPtsUs = -1L;
                            lastPtsUs = -1L;
                            continue;
                        }
                    }
                    // 首帧解出后启动音频（与视频从同一媒体时间起播；
                    // 音频真正出声后成为主时钟）
                    startAudioIfNeeded(resolved);
                    // #region debug-point B:first-frame
                    debugPoint("B", "VideoPlayer.decodeLoop:first-frame",
                            "[DEBUG] first video frame decoded",
                            "screenId", screenId,
                            "ptsUs", ptsUs,
                            "width", frame.imageWidth,
                            "height", frame.imageHeight);
                    // #endregion
                }

                // 直接按帧的真实媒体时间呈现：seek 后从"目标前关键帧"开始的
                // 旧帧会因 PTS 早于音频而快速补放追上，无需"滚帧丢弃"，
                // 彻底避免部分容器向后 seek 时卡死在丢帧循环里
                long dueAbsMs = ptsUs / 1000L;
                if (!fillSlot(frame, dueAbsMs)) {
                    // 转换失败（罕见格式）：跳过该帧
                    continue;
                }
            }
        } catch (InterruptedException ie) {
            LOGGER.info("[CinemaForYou] 解码线程被中断退出: {}", screenId);
        } catch (Exception e) {
            error = "解码失败: " + e.getMessage();
            // #region debug-point B:video-exception
            debugPoint("B", "VideoPlayer.decodeLoop:exception",
                    "[DEBUG] video decode exception",
                    "screenId", screenId,
                    "error", String.valueOf(e));
            // #endregion
            LOGGER.error("[CinemaForYou] 视频解码错误", e);
        } finally {
            running.set(false);
            // 音频收尾（幂等）：解码异常时避免音频无人管理一直响
            AudioPlayer a = audioPlayer;
            if (a != null) {
                a.stop();
            }
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
                try { grabber.release(); } catch (Exception ignored) {}
                grabber = null;
            }
            // 原生 grabber 已释放完毕：唤醒可能在等它的新播放器（见 LAST_RELEASED）
            nativeClosed.countDown();
        }
    }

    /** 启动音频会话（幂等：只创建一次）。音频流若不存在会很快自我结束。 */
    private void startAudioIfNeeded(UrlResolver.ResolvedSource resolved) {
        if (audioPlayer != null) return;
        if (!running.get()) return; // release() 已介入：不要再开新会话
        AudioPlayer ap = new AudioPlayer(screenId, screen, resolved.audioUrl(), sourceUrl);
        audioPlayer = ap;
        // 不可精确定位的流（HLS）：音频也从起点开始，两侧自然对齐，
        // 避免按服务端位置起播导致永久等待/回溯死循环
        ap.start(seekCapable ? segmentStartMs : 0L);
    }

    /** 标记视频 EOF；EOF 后 tick() 会等音频播完再向服务端发停止。 */
    private void markVideoEof() {
        videoEofAtMs = System.currentTimeMillis();
        LOGGER.info("[CinemaForYou] 视频结束，等待音频收尾 (eofAt={}ms)", videoEofAtMs);
    }

    /** 视频先结束后的收尾等待：等音频播完（含排空）或超时，期间保持末帧画面。 */
    private void waitAudioFinished() throws InterruptedException {
        long deadline = System.currentTimeMillis() + EOF_MAX_WAIT_MS;
        while (running.get() && !paused.get()) {
            AudioPlayer a = audioPlayer;
            if (a == null || a.isFinished() || System.currentTimeMillis() > deadline) {
                return;
            }
            updateMasterClock(false);
            Thread.sleep(50);
        }
    }

    /** 安全 seek（当前 grabber）：成功返回 true；失败（流不支持/异常）返回 false。 */
    private boolean safeSeek(long targetMs) {
        return seekGrabber(grabber, targetMs);
    }

    private static boolean seekGrabber(FFmpegFrameGrabber g, long targetMs) {
        if (g == null) return false;
        try {
            // 与音频侧一致的普通 seek 方式
            // 个别实现不支持时回退普通 setTimestamp
            g.setTimestamp(Math.max(0L, targetMs) * 1000L);
            return true;
        } catch (Exception e) {
            try {
                g.setTimestamp(Math.max(0L, targetMs) * 1000L);
                return true;
            } catch (Exception e2) {
                LOGGER.warn("[CinemaForYou] seek 到 {}ms 失败（流可能不支持 seek）: {}",
                        targetMs, e2.toString());
                return false;
            }
        }
    }

    /**
     * seek 目标超出片尾时的收尾：走既有 EOF 链路（启动音频收尾 → 标记 EOF →
     * 等音频排空），随后由渲染线程按当前屏幕播完行为执行动作（循环/下一项/
     * 播完暂停/停止；申请授权内容则按既有规则播完即停）。
     *
     * @return true = 已按播完处理，解码循环应结束本次播放
     */
    private boolean seekPastEndReached(UrlResolver.ResolvedSource resolved)
            throws InterruptedException {
        if (!seekPastEnd) return false;
        seekPastEnd = false;
        startAudioIfNeeded(resolved);
        markVideoEof();
        waitAudioFinished();
        return true;
    }

    /** 处理一次 seek：重置 PTS 映射、清空排队帧、重锚时钟基准。 */
    private void applySeek(long targetMs) {        pendingSeekMs = -1L;
        if (durationMs > 0 && targetMs >= durationMs) {
            // 跳转目标超出片尾：按"正常播完"处理——交给既有 EOF 链路决定后续
            // （循环/自动下一项/播完暂停/停止；申请授权的内容则按既有规则"播完即停"）。
            // 注意：绝不能回退成"从头播放"，那会导致跳转越界后从头加速追画面。
            LOGGER.warn("[CinemaForYou] seek 目标 {}ms 已超过时长 {}ms，按正常播完处理",
                    targetMs, durationMs);
            resetSlots();
            firstPtsUs = -1L;
            lastPtsUs = -1L;
            consecutiveNulls = 0;
            seekFixTries = 0;
            lastSeekHandledAtMs = System.currentTimeMillis();
            segmentStartMs = durationMs;
            masterPosMs = durationMs;
            awaitingFirstUpload = true;   // 已到片尾，主时钟冻结在片尾等待既有 EOF 链路收尾
            wallFallback = true;
            wallBasePosMs = durationMs;
            wallBaseWallMs = System.currentTimeMillis();
            resyncArmed = false;
            seekPastEnd = true;
            return;
        }
        if (targetMs < DIRECT_SEEK_MIN_MS) {
            // 回开头/小目标：setTimestamp 在部分 WebM 上会错误落到末尾关键帧（实测），
            // 改为整体重开解码流——新 grabber 天然从 0 开始，随后帧会快速追上目标
            reopenRequested = true;
            LOGGER.info("[CinemaForYou] seek 目标 {}ms 很小，改为重开解码流回到开头", targetMs);
        } else if (!safeSeek(targetMs)) {
            // 本次 seek 失败：该流不支持精确定位（HLS 等），标记后不再回溯重试
            seekCapable = false;
            // 标记重开解码流（reopen 时会再次尝试定位）
            reopenRequested = true;
        }
        resetSlots();
        firstPtsUs = -1L;
        lastPtsUs = -1L;
        consecutiveNulls = 0;
        seekFixTries = 0;
        lastSeekHandledAtMs = System.currentTimeMillis();
        segmentStartMs = Math.max(0L, targetMs);
        wallFallback = true;
        wallBasePosMs = segmentStartMs;
        wallBaseWallMs = System.currentTimeMillis();
        masterPosMs = segmentStartMs;
        awaitingFirstUpload = true;   // 首帧上屏前冻结主时钟，定位/解码耗时不进入播放位置
        resyncArmed = false;
    }

    // ───────────── 解码流打开 / 重开 ─────────────

    /** 创建并配置好抓帧器（未 start）。同一配置在重开解码流时复用。 */
    private FFmpegFrameGrabber openConfiguredGrabber(String url) throws Exception {
        return openConfiguredGrabber(url, true);
    }

    /** @param withSourceHeaders 是否附带按来源生成的 UA/Referer 请求头（防盗链站可能需要去掉）。 */
    private FFmpegFrameGrabber openConfiguredGrabber(String url, boolean withSourceHeaders)
            throws Exception {
        // 瘦身版：natives 由 NativeRuntime 按需下载并注册，这里等它就绪
        if (!NativeRuntime.ensureBlocking()) {
            throw new Exception("ffmpeg natives unavailable: "
                    + NativeRuntime.failureReason());
        }
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(url);
        String dec = alphaDecoderName;
        if (dec == null) {
            // 实例未探测过：查静态缓存（循环重建 VideoPlayer 时命中可省掉双开探测，
            // openConfiguredGrabber 这次直接用 libvpx 打开，switchToVpxAlphaDecoder
            // 见缓存命中直接 return，不再 stop+release+重开）
            String cached = ALPHA_DECODER_CACHE.get(url);
            if (cached != null && !cached.isEmpty()) dec = cached;
        }
        if (dec != null) {
            // WebM 透明通道（alpha_mode=1）只有 libvpx 解码器能解出来
            g.setVideoCodecName(dec);
        }
        g.setOption("rtsp_transport", "tcp");
        // 强制 RGBA：保留 alpha 通道（透明视频素材用）。选 RGBA 而非 BGRA 是为了让
        // 内存字节序与 Minecraft 纹理要求的完全一致，解码线程可以整块 int 拷贝，
        // 避免逐像素的 Java 通道交换（1080p 每帧约 5ms，占解码线程三成开销）。
        g.setPixelFormat(avutil.AV_PIX_FMT_RGBA);
        int decodeHeight = effectiveDecodeHeight(screen);
        if (decodeHeight > 0) {
            // 只缩小不放大：高度取 min(decodeHeight, ih)，宽度 -2 自动取偶数
            g.setVideoOption("vf", "scale=-2:min(" + decodeHeight + ",ih)");
        }
        String headers = withSourceHeaders ? UrlResolver.ffmpegHttpHeaders(sourceUrl, url) : null;
        if (headers != null) {
            g.setOption("headers", headers);
            // 复用同一 TCP 连接发后续 Range 请求：FFmpeg 默认每个请求新建连接，
            // 文件探测/跳读会产生大量小请求，经远程隧道时每次建连都很慢
            g.setOption("http_persistent", "1");
            // TCP 建连超时（µs）：服务器不可达/被防火墙丢弃时快速失败而非无限挂起
            g.setOption("timeout", "15000000");
            // 网络读取超时（µs）：45s 内短暂的网络停顿不中断播放，
            // 过长才让读失败（由解码循环做"停顿等待/重开"，避免误判结束）
            g.setOption("rw_timeout", "45000000");
        }
        // 媒体流代理：TikTok/YouTube 等 CDN 域名与网页一样可能直连不通，
        // 配置代理后拉流也走代理（本地服务器媒体与 B站/抖音等直连友好站自动排除）
        if (UrlResolver.proxyEnabledFor(url)) {
            g.setOption("http_proxy", UrlResolver.effectiveProxy());
            LOGGER.info("[CinemaForYou] 媒体流走代理: {}", trimForLog(url));
        }
        return g;
    }

    /**
     * 首次打开后探测：WebM（VP8/VP9）容器声明 alpha_mode=1 时，透明通道只有
     * libvpx 解码器能解出来——FFmpeg 原生 vp8/vp9 解码器会直接丢弃 alpha
     * （画面照常播，但透明背景变成不透明底）。命中则换解码器重开一次，
     * 判定结果缓存，后续重开解码流自动复用。
     *
     * <p>只对这些文件换解码器：绝不全局强设解码器名——H.264 等编码强推
     * libvpx 会在 avcodec_open2 直接失败，整个视频打不开。
     */
    private FFmpegFrameGrabber switchToVpxAlphaDecoder(FFmpegFrameGrabber g, String url,
            boolean withSourceHeaders) throws Exception {
        if (alphaDecoderProbed) return g;
        alphaDecoderProbed = true;
        // 静态缓存命中：同一源之前已探测过，openConfiguredGrabber 已按缓存设好解码器，
        // 无需再 stop+release+重开 grabber（这是循环播放延迟和 libvpx 累积卡死的关键）
        String cached = ALPHA_DECODER_CACHE.get(url);
        if (cached != null) {
            if (!cached.isEmpty()) alphaDecoderName = cached;
            return g;
        }
        // 先认编码：只有 VP8/VP9 才可能涉及 libvpx 切换。
        // 非 VP8/VP9 永久缓存空串（H.264 等强推 libvpx 会 avcodec_open2 失败）。
        int codec = g.getVideoCodec();
        String decoder = codec == avcodec.AV_CODEC_ID_VP9 ? "libvpx-vp9"
                : codec == avcodec.AV_CODEC_ID_VP8 ? "libvpx" : null;
        if (decoder == null) {
            ALPHA_DECODER_CACHE.put(url, ""); // 非 VP8/VP9：永久跳过
            return g;
        }
        // 读容器 alpha 标记。读不到（瞬时异常/null）时保守处理：VP8/VP9 一律切 libvpx——
        // libvpx 解普通无 alpha 的 VP8/VP9 也完全正常（仅软解稍慢），但能保证 alpha
        // 绝不因元数据读取失败而被原生解码器丢弃。只有"明确读到 0"才确信无 alpha。
        String alphaMode = null;
        boolean metaReadFailed = false;
        try {
            alphaMode = g.getVideoMetadata("alpha_mode");
        } catch (Exception e) {
            metaReadFailed = true;
        }
        if (!metaReadFailed
                && (alphaMode == null || "0".equals(alphaMode.trim()) || alphaMode.trim().isEmpty())) {
            // 明确无 alpha：永久缓存空串，用原生解码器播放
            ALPHA_DECODER_CACHE.put(url, "");
            return g;
        }

        LOGGER.info("[CinemaForYou] VP8/VP9 源切换 {} 解码器保 alpha（alpha_mode={}）: {}",
                decoder, metaReadFailed ? "读取失败,保守切换" : String.valueOf(alphaMode),
                trimForLog(url));
        try {
            // 只 release 不 stop：JavaCV 的 release() 内部已包含 close/stop 逻辑，
            // 显式再调 stop() 在某些 FFmpeg 版本下会重复关闭 codec context，
            // 导致 native 状态异常、新 grabber 打开后首帧永久阻塞（首次播放卡死的根因之一）。
            try { g.release(); } catch (Exception ignored) {}
            // 短暂让出，让 native 侧完成解码器上下文销毁，避免新旧 grabber 资源竞争
            Thread.sleep(50);
            alphaDecoderName = decoder;
            awaitPreviousNativeClose();
            FFmpegFrameGrabber g2 = openConfiguredGrabber(url, withSourceHeaders);
            g2.start();
            // 成功才写缓存：瞬时 native 资源紧张导致 start 失败时绝不写空串，
            // 否则该 URL 会被永久污染为"原生解码器"——循环几十遍后偶发不透明
            // （绿幕重现）的根因。下次重建实例会重新尝试 libvpx。
            ALPHA_DECODER_CACHE.put(url, decoder);
            return g2;
        } catch (Exception e) {
            // 换解码器失败：本次回退默认解码器继续播放（背景不透明，但画面正常）。
            // 不写缓存：这是瞬时失败，下次循环重建必须重新尝试 libvpx。
            alphaDecoderName = null;
            LOGGER.warn("[CinemaForYou] libvpx 解码器重开失败（瞬时，不缓存），本次回退默认解码器: {}",
                    String.valueOf(e.getMessage()));
            FFmpegFrameGrabber fallback = openConfiguredGrabber(url, withSourceHeaders);
            fallback.start();
            return fallback;
        }
    }

    /**
     * 等上一代播放器把原生 grabber 释放完，再打开自己的。
     *
     * <p>切换视频（尤其透明 VP9/libvpx ↔ 不透明 H.264）时，旧实例的 grabber 由它的解码
     * 线程在 finally 里释放；若新实例抢先打开，两代原生解码器状态交叉会导致新播放器
     * 打不开/不吐帧（画面卡在上一帧）。这里最多等 2s，超时也继续（最坏退化为旧行为），
     * 等待发生在新播放器自己的解码线程上，不阻塞游戏主线程。
     */
    private static void awaitPreviousNativeClose() {
        VideoPlayer prev = LAST_RELEASED.get();
        if (prev == null) return;
        try {
            if (!prev.nativeClosed.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                LOGGER.warn("[CinemaForYou] 上一代 grabber 未在 2s 内释放完，仍继续打开新的（可能卡顿）");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * （解码线程内）整体重开解码流并定位到片段起点。
     * 用于：seek 连续失败、滚帧卡死、渲染端看门狗升级等情况。
     */
    private void reopenGrabber() {
        resyncArmed = false;
        consecutiveNulls = 0;
        seekFixTries = 0;
        try {
            FFmpegFrameGrabber old = grabber;
            grabber = null;
            if (old != null) {
                // 只 release 不 stop：release 内部已 close，重复 stop 可能导致 native 状态异常
                try { old.release(); } catch (Exception ignored) {}
            }
            if (videoResolvedUrl == null) return;
            // 上次探测若因瞬时失败没拿到 libvpx 解码器（alphaDecoderName==null 且缓存
            // 无结论），允许重新探测一次——否则实例一旦 fallback 到原生解码器，
            // alphaDecoderProbed=true 会让后续每次循环都永远丢 alpha（绿幕重现）。
            if (alphaDecoderName == null && !ALPHA_DECODER_CACHE.containsKey(videoResolvedUrl)) {
                alphaDecoderProbed = false;
            }
            awaitPreviousNativeClose();
            FFmpegFrameGrabber g = openConfiguredGrabber(videoResolvedUrl);
            g.start();
            g = switchToVpxAlphaDecoder(g, videoResolvedUrl, true);
            long target = Math.max(0L, segmentStartMs);
            // 小目标不 seek：新 grabber 已在 0，直接解码让 PTS 追上目标即可
            // （这一档 setTimestamp 在部分 WebM 上会错误跳到末尾，实测复现）
            boolean ok = target >= DIRECT_SEEK_MIN_MS && seekGrabber(g, target);
            grabber = g;
            lastSeekHandledAtMs = System.currentTimeMillis();
            if (!ok) {
                LOGGER.warn("[CinemaForYou] 重开后定位 {}ms 失败，从头/当前位置播放", target);
            }
            resetSlots();
            firstPtsUs = -1L;
            lastPtsUs = -1L;
            awaitingFirstUpload = true;   // 首帧上屏前冻结主时钟，重开耗时不进入播放位置
            lastFrameAtMs = System.currentTimeMillis();
            LOGGER.info("[CinemaForYou] 屏幕 {} 解码流已重开 @ {}ms (ok={})", screenId, target, ok);
        } catch (Exception e) {
            error = "重开解码流失败: " + e.getMessage();
            LOGGER.error("[CinemaForYou] 重开解码流失败", e);
        }
    }

    // ───────────── 主时钟 ─────────────

    /**
     * 更新主时钟：音频可听位置优先，其次墙钟回退。
     * 在解码线程的每个等待/循环节拍调用。
     *
     * @param frozen 是否处于暂停（暂停时墙钟不回拨、音频也已停）
     */
    private void updateMasterClock(boolean frozen) {
        long nowWall = System.currentTimeMillis();
        if (awaitingFirstUpload) {
            // 本片段首帧尚未上屏：主时钟钉在片段起点，不前进也不累积——否则"解析链接→
            // 打开 grabber→解码首帧"的启动耗时会被计入播放位置，首帧一到就落后时钟、
            // 被连续快放追赶，表现为开头 2~3 秒被"快放"跳过。
            // 这里刻意不刷新墙钟基准：保留它才能让"启动后仍无帧"的看门狗按原节奏触发重开。
            wallFallback = true;
            masterPosMs = segmentStartMs;
            return;
        }
        AudioPlayer a = audioPlayer;
        if (a != null && a.hasLiveAudio()) {
            // 音频时钟为主：顺带持续刷新墙钟回退基准，音频结束后无缝切换
            masterPosMs = Math.max(0L, a.getPositionMs());
            wallFallback = false;
            wallBasePosMs = masterPosMs;
            wallBaseWallMs = nowWall;
            return;
        }
        if (a != null && !a.isFinished()) {
            // 音频会话已建但尚未出声（启动中）：呈现被 tick 门控暂停，
            // 这里只把基准钉在片段起点，避免启动延迟累积成跳变
            wallFallback = true;
            wallBasePosMs = segmentStartMs;
            wallBaseWallMs = nowWall;
            if (!frozen) {
                masterPosMs = wallBasePosMs;
            }
            return;
        }
        // 无音频 / 音频已结束：墙钟回退，从最后已知位置继续
        wallFallback = true;
        if (a != null && a.isFinished()) {
            // 音频结束瞬间，把基准锚到最后的可听位置，防止回跳到片段起点
            long end = a.getEndPositionMs();
            if (end > wallBasePosMs) {
                wallBasePosMs = end;
                wallBaseWallMs = nowWall;
            }
        }
        if (frozen) {
            // 暂停中：每拍重锚基准，暂停时长不会累计进墙钟
            wallBasePosMs = masterPosMs;
            wallBaseWallMs = nowWall;
        } else {
            long newMaster = wallBasePosMs + (nowWall - wallBaseWallMs);
            // 钳制：墙钟回退不得超过已知时长——grabber 卡死不返回帧也不 EOF 时，
            // 墙钟会无限增长导致进度条跑出视频总时间。限制在 durationMs，让画面停在末帧状态，
            // 等看门狗/循环逻辑处理。
            if (durationMs > 0 && newMaster > durationMs) {
                newMaster = durationMs;
                // 锚到时长，避免每拍都触发钳制（减少漂移修正抖动）
                wallBasePosMs = durationMs;
                wallBaseWallMs = nowWall;
            }
            masterPosMs = newMaster;
        }
    }

    // ───────────── 帧槽 ─────────────

    /**
     * 等待出现空闲帧槽（已满则小幅休眠，保持 running 响应性）。
     *
     * <p>关键：等待期间也要响应"重开解码流 / 新 seek"请求——
     * 画面冻结时若解码卡在槽满等待里，看门狗的重开指令必须能尽快生效，
     * 否则会一直停在原地（日志里"重开解码流"迟迟不执行的原因）。
     */
    private void waitForEmptySlot() throws InterruptedException {
        while (running.get()) {
            if (reopenRequested || pendingSeekMs >= 0) {
                return; // 交回主循环处理重开/seek
            }
            synchronized (frameLock) {
                for (int i = 0; i < slotCount; i++) {
                    if (slotState[i] == SLOT_EMPTY) {
                        return;
                    }
                }
            }
            updateMasterClock(false);
            Thread.sleep(2);
        }
    }

    /** seek 后丢弃所有排队帧；显示内容保持旧帧直到新片段首帧呈现。
     *  上传中的槽不重置（渲染线程马上会把它置回空），避免覆盖正在读的数组。 */
    private void resetSlots() {
        synchronized (frameLock) {
            for (int i = 0; i < slotCount; i++) {
                if (slotState[i] != SLOT_UPLOADING) {
                    slotState[i] = SLOT_EMPTY;
                    slotDueMs[i] = 0L;
                }
            }
        }
    }

    /**
     * 把一帧解到空闲帧槽（SLOT_EMPTY → SLOT_DECODING → SLOT_FILLED）。
     * 优先走 BGR24 手工转换零分配快路径，异常格式回退 Java2DFrameConverter；
     * 转换完成后在同一解码线程上生成 mip 链（逐级减半均值，供三线性采样）。
     *
     * <p>尺寸变化需要整体重分配缓冲时，会先等上传中的槽结束，避免替换掉
     * 渲染线程正在读取的数组。
     */
    private boolean fillSlot(Frame frame, long dueAbsMs) throws InterruptedException {
        int w = frame.imageWidth;
        int h = frame.imageHeight;
        if (w <= 0 || h <= 0) return false;

        int[] dest = null;
        for (int attempt = 0; attempt < 500 && dest == null; attempt++) {
            synchronized (frameLock) {
                boolean uploading = false;
                for (int i = 0; i < slotCount; i++) {
                    if (slotState[i] == SLOT_UPLOADING) {
                        uploading = true;
                        break;
                    }
                }
                int mipCount = VideoFrameTexture.mipLevelCount(w, h);
                boolean needResize = slotPixels == null || slotPixels[0] == null
                        || slotPixels[0][0].length != w * h
                        || slotPixels[0].length != mipCount
                        || frameWidth != w || frameHeight != h;
                if (!needResize) {
                    for (int i = 0; i < slotCount; i++) {
                        if (slotState[i] == SLOT_EMPTY) {
                            slotState[i] = SLOT_DECODING;
                            dest = slotPixels[i][0];
                            break;
                        }
                    }
                } else if (!uploading) {
                    // 此刻没有上传进行中，安全整体重分配（每槽一条 mip 链）
                    // 槽深自适应：内存预算 ÷ 单帧字节（含 mip 约 ×1.4），
                    // 再与"目标领先秒数 × 帧率"取小，保证不超内存又有足够抗抖动深度
                    long perFrameBytes = (long) w * h * 4L * 14L / 10L;
                    int byMem = (int) Math.max(6L,
                            (SLOT_MEMORY_MB * 1048576L) / Math.max(1L, perFrameBytes));
                    double fps = 30.0;
                    try {
                        double f = grabber != null ? grabber.getFrameRate() : 0.0;
                        if (f > 1.0 && f < 240.0) fps = f;
                    } catch (Exception ignored) {}
                    int byTime = (int) Math.max(6L, Math.round(BUFFER_TARGET_SECONDS * fps));
                    int newCount = Math.min(MAX_SLOTS, Math.min(byMem, byTime));
                    if (newCount != slotCount) {
                        LOGGER.info("[CinemaForYou] 帧槽深度自适应: {}（{}x{}，预算{}MB/目标{}s）",
                                newCount, w, h, SLOT_MEMORY_MB, BUFFER_TARGET_SECONDS);
                    }
                    slotCount = newCount;
                    int[][][] fresh = new int[slotCount][mipCount][];
                    for (int i = 0; i < slotCount; i++) {
                        for (int k = 0; k < mipCount; k++) {
                            fresh[i][k] = new int[
                                    Math.max(1, w >> k) * Math.max(1, h >> k)];
                        }
                    }
                    slotPixels = fresh;
                    frameWidth = w;
                    frameHeight = h;
                    for (int i = 0; i < slotCount; i++) {
                        slotState[i] = SLOT_EMPTY;
                        slotDueMs[i] = 0L;
                    }
                    slotState[0] = SLOT_DECODING;
                    dest = fresh[0][0];
                }
            }
            if (dest == null) {
                updateMasterClock(false);
                Thread.sleep(2); // 等上传结束再试
            }
        }
        if (dest == null) return false;

        boolean ok = convertFrameToAbgr(frame, dest, w, h);
        if (ok) {
            // 透明内容检测：前若干帧抽样扫描 alpha（BGRA 解码后有效）。
            // 一旦发现 <250 的像素即标记该源为透明视频（渲染切混合管线）
            if (!translucentContent && alphaCheckFrames < 12) {
                alphaCheckFrames++;
                int len = w * h;
                int step = len > 600000 ? 3 : 1;
                int[] px = dest;
                for (int i = 0; i < len; i += step) {
                    if (((px[i] >>> 24) & 0xFF) < 250) {
                        translucentContent = true;
                        LOGGER.info("[CinemaForYou] 检测到透明视频内容（启用混合渲染）: {}x{}", w, h);
                        break;
                    }
                }
            }
            // 解码线程生成 mip 层：每层 = 上一层 2×2 均值（奇数边钳制最后一行/列）
            int[][] chain = null;
            synchronized (frameLock) {
                chain = slotPixels[idxOf(dest)];
            }
            ok = generateMipChain(chain, w, h);
        }
        if (!ok) {
            synchronized (frameLock) {
                slotState[idxOf(dest)] = SLOT_EMPTY;
            }
            return false;
        }
        synchronized (frameLock) {
            int i = idxOf(dest);
            slotState[i] = SLOT_FILLED;
            slotDueMs[i] = Math.max(0L, dueAbsMs);
        }
        lastFrameAtMs = System.currentTimeMillis();
        if (timelineFirstDecodeMs == 0L) timelineFirstDecodeMs = lastFrameAtMs;
        return true;
    }

    private int idxOf(int[] pixels) {
        for (int i = 0; i < slotCount; i++) {
            if (slotPixels != null && slotPixels[i] != null
                    && slotPixels[i][0] == pixels) return i;
        }
        return 0;
    }

    /**
     * 由 0 级帧逐级生成 mip 链（2×2 盒式均值，ABGR 各通道分开平均）。
     * 奇数边长时最后一行/列与自身配对（等价边缘钳制）。
     */
    private static boolean generateMipChain(int[][] chain, int baseW, int baseH) {
        if (chain == null || chain.length < 2) return chain != null && chain.length == 1;
        int srcW = baseW;
        int srcH = baseH;
        for (int k = 1; k < chain.length; k++) {
            int dstW = Math.max(1, srcW >> 1);
            int dstH = Math.max(1, srcH >> 1);
            int[] src = chain[k - 1];
            int[] dst = chain[k];
            if (dst.length < dstW * dstH || src.length < srcW * srcH) return false;
            for (int y = 0; y < dstH; y++) {
                int sy0 = Math.min(y * 2, srcH - 1);
                int sy1 = Math.min(y * 2 + 1, srcH - 1);
                int rowA = sy0 * srcW;
                int rowB = sy1 * srcW;
                int rowDst = y * dstW;
                for (int x = 0; x < dstW; x++) {
                    int sx0 = Math.min(x * 2, srcW - 1);
                    int sx1 = Math.min(x * 2 + 1, srcW - 1);
                    dst[rowDst + x] = avg4(
                            src[rowA + sx0], src[rowA + sx1],
                            src[rowB + sx0], src[rowB + sx1]);
                }
            }
            srcW = dstW;
            srcH = dstH;
        }
        return true;
    }

    /** 两个 ABGR 像素逐通道平均（高低字节分组避免进位串扰）。 */
    private static int avg2(int a, int b) {
        int lo = (a & 0x00FF00FF) + (b & 0x00FF00FF);
        int hi = ((a >>> 8) & 0x00FF00FF) + ((b >>> 8) & 0x00FF00FF);
        return ((lo >> 1) & 0x00FF00FF) | ((hi >> 1) << 8);
    }

    /** 2×2 盒式平均。 */
    private static int avg4(int a, int b, int c, int d) {
        return avg2(avg2(a, b), avg2(c, d));
    }

    /**
     * BGR24/BGRA ByteBuffer 手工转 ABGR int 数组（BGRA 时保留 alpha 通道）。
     * 像素行间可能有对齐填充，必须按 {@link Frame#imageStride} 逐行拷贝。
     */
    private boolean convertFrameToAbgr(Frame frame, int[] dest, int w, int h) {
        Buffer[] planes = frame.image;
        if (planes == null || planes.length == 0) return false;
        Buffer p0 = planes[0];
        if (!(p0 instanceof ByteBuffer srcRaw)) return false;
        if (frame.imageDepth != Frame.DEPTH_UBYTE) return false;

        ByteBuffer src = srcRaw.duplicate();
        int stride = frame.imageStride > 0 ? frame.imageStride : w * 4;
        // 快路径：RGBA 打包格式 + 行内无填充 => 字节序已与纹理要求一致，
        // 整块 int 拷贝即可（零逐像素开销）
        if (stride == w * 4 && src.capacity() >= w * h * 4) {
            IntBuffer ib = src.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            ib.get(dest, 0, w * h);
            return true;
        }
        int bpp = stride / w;                 // 3=BGR24, 4=BGRA
        if (bpp != 3 && bpp != 4) {
            return convertViaAwt(frame, dest, w, h);
        }
        int capacity = src.capacity();
        int rows = Math.min(h, Math.max(0, capacity) / Math.max(1, stride));
        if (rows < h) {
            for (int i = w * rows; i < w * h; i++) dest[i] = 0xFF000000; // 缺行补黑
        }
        for (int y = 0; y < rows; y++) {
            int rowBase = y * stride;
            int idx = y * w;
            int limit = Math.min(capacity, rowBase + w * bpp);
            src.position(rowBase);
            int x = 0;
            while (rowBase + x * bpp + 2 < limit) {
                int bl = src.get() & 0xFF;
                int g = src.get() & 0xFF;
                int r = src.get() & 0xFF;
                int a = 255;
                if (bpp == 4) a = src.get() & 0xFF; // BGRA 的第 4 字节
                dest[idx + x] = (a << 24) | r | (g << 8) | (bl << 16);
                x++;
            }
            for (; x < w; x++) dest[idx + x] = 0xFF000000;
        }
        return true;
    }

    /** 回退路径：Java2DFrameConverter → BufferedImage → getRGB（极少触发）。 */
    private boolean convertViaAwt(Frame frame, int[] dest, int w, int h) {
        try {
            BufferedImage img = new Java2DFrameConverter().convert(frame);
            if (img == null) return false;
            img.getRGB(0, 0, w, h, dest, 0, w);
            for (int i = 0; i < dest.length; i++) {
                int argb = dest[i];
                dest[i] = 0xFF000000
                        | (argb & 0x0000FF00)
                        | ((argb & 0x00FF0000) >> 16)
                        | ((argb & 0x000000FF) << 16);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[CinemaForYou] AWT 回退转换失败", t);
            return false;
        }
    }

    // ───────────── 主线程 tick（客户端 tick 与每渲染帧均可调用，幂等） ─────────────

    /**
     * 把"已到呈现时间"的最新帧上传到纹理。必须在渲染线程执行。
     *
     * <p>调用方：客户端 tick（20Hz 兜底）+ {@code ScreenRenderer} 每渲染帧
     * （实际上传节奏 = 渲染帧率）。纹理无到期新帧时几乎零开销。
     */
    public void tick() {
        if (pendingRelease) {
            pendingRelease = false;
            releaseTexture();
            return;
        }
        if (audioPlayer != null) {
            audioPlayer.tickSpatial();
        }

        // 视频 EOF 后的行为：等音频播完（含排空）再执行播放模式动作（循环/下一集/
        // 播完暂停/停止），避免掐尾音；无音频或超时则直接执行。
        if (videoEofAtMs > 0 && !endReported) {
            AudioPlayer a = audioPlayer;
            boolean audioDone = a == null || a.isFinished();
            if (audioDone || System.currentTimeMillis() - videoEofAtMs > EOF_MAX_WAIT_MS) {
                handleEndOfVideo();
            }
        }
        if (error != null) return;

        // 音频会话已建但还没真正出声：先等一小段（声卡/网络启动需要时间）。
        // 超过上限仍无声则画面先行呈现——否则音频源无响应时画面会被永久
        // 门控住，表现为"播放后无画面、无报错"。
        AudioPlayer ap = audioPlayer;
        if (ap != null && !ap.isFinished() && !ap.hasLiveAudio()) {
            long nowGate = System.currentTimeMillis();
            if (audioGateStartMs == 0L) {
                audioGateStartMs = nowGate;
            }
            // 本地源用短门控：本地文件音频启动不该等几秒，超过即画面先行。
            // 网络/在线源仍给足 3.5s（远程音频握手+缓冲确实需要时间）。
            long gateMax = isLocalSource() ? AUDIO_GATE_LOCAL_MAX_MS : AUDIO_GATE_MAX_MS;
            if (nowGate - audioGateStartMs < gateMax) {
                return;
            }
            if (!audioGateLogged) {
                audioGateLogged = true;
                audioGateWaitedMs = nowGate - audioGateStartMs;
                LOGGER.warn("[CinemaForYou] 音频 {}ms 仍未出声，画面先行呈现（音频可能无响应）",
                        gateMax);
            }
        } else {
            audioGateStartMs = 0L;
        }

        // ── 冻结看门狗：画面长时间没更新但音频在走（连续快速 seek 卡死场景） ──
        // 判据用"实际显示帧"（lastUploadAtMs），解码线程正常但不显示同样能触发：
        // 第一步补 seek 到当前音频位置；仍不显示则升级为整体重开解码流（带 3s 冷却）。
        // 无音频流的透明视频（转换生成的 WebM）循环重开后如果首帧没成功上传，
        // 旧看门狗因"lastUploadAtMs==0"和"无音频"两个前置条件不满足而永远不触发，
        // 导致画面永久冻结。此处补一条独立的"启动后无帧"重开路径。
        if (error == null && videoEofAtMs <= 0 && running.get() && !paused.get()
                && decodeThread != null && decodeThread.isAlive()) {
            long nowMs = System.currentTimeMillis();
            // 基准：已上传过帧用 lastUploadAtMs；否则用 VideoPlayer 启动时间 wallBaseWallMs
            long idleBase = lastUploadAtMs > 0L ? lastUploadAtMs : wallBaseWallMs;
            long idle = nowMs - idleBase;
            if (idle > 2500) {
                AudioPlayer aa = audioPlayer;
                boolean audioMoving = aa != null && aa.hasLiveAudio();
                if (audioMoving) {
                    if (!resyncArmed) {
                        resyncArmed = true;
                        resyncArmedAtMs = nowMs;
                        pendingSeekMs = Math.max(0L, masterPosMs);
                        LOGGER.warn("[CinemaForYou] 画面 {}ms 未更新，补 seek 到 {}ms", idle, masterPosMs);
                    } else if (nowMs - resyncArmedAtMs > 2500
                            && nowMs - lastForcedActionAtMs > 3000) {
                        lastForcedActionAtMs = nowMs;
                        resyncArmed = false;
                        reopenRequested = true;
                        LOGGER.warn("[CinemaForYou] 补 seek 后画面仍未更新，重开解码流");
                    }
                } else if (lastUploadAtMs == 0L
                        && nowMs - wallBaseWallMs > 3500
                        && nowMs - lastForcedActionAtMs > 3000) {
                    // 无音频流且从未成功上传帧（典型：循环播放重开的透明 WebM）：
                    // 本地文件初始化用不了几秒，3.5s 仍无画面即判定异常，直接重开解码流
                    // （更长的等待会让用户明显感觉"卡住后等很久才恢复"）。
                    lastForcedActionAtMs = nowMs;
                    reopenRequested = true;
                    LOGGER.warn("[CinemaForYou] 无音频流且 {}ms 内无画面上传，重开解码流", idle);
                }
            } else if (idle < 200) {
                resyncArmed = false;
            }
        }

        long master = masterPosMs;
        int[][] chain;
        int w;
        int h;
        synchronized (frameLock) {
            int pick = -1;
            long bestDue = Long.MIN_VALUE;
            for (int i = 0; i < slotCount; i++) {
                if (slotState[i] == SLOT_FILLED) {
                    long due = slotDueMs[i];
                    if (due <= master + PRESENTATION_LEAD_MS && due > bestDue) {
                        bestDue = due;
                        pick = i;
                    }
                }
            }
            if (pick < 0 || slotPixels == null) return;
            slotState[pick] = SLOT_UPLOADING;
            chain = slotPixels[pick];
            w = frameWidth;
            h = frameHeight;
        }
        if (chain == null || chain.length == 0 || w <= 0 || h <= 0) {
            releaseSlotByPixels(chain != null ? chain[0] : null);
            return;
        }

        RenderSystem.assertOnRenderThread();

        if (texture == null) {
            texture = new VideoFrameTexture("cinema_" + screenId);
            texture.init(w, h);
            textureId = Identifier.fromNamespaceAndPath("cinemaforyou", "video/" + screenId + "_" + instanceSeq);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            LOGGER.info("[CinemaForYou] 屏幕 {} 纹理已创建 ({}x{}, {} mip)", screenId, w, h, texture.levelCount());
        } else if (texture.levelCount() != chain.length
                || texture.levelWidth(0) != w || texture.levelHeight(0) != h) {
            releaseTexture();
            texture = new VideoFrameTexture("cinema_" + screenId);
            texture.init(w, h);
            textureId = Identifier.fromNamespaceAndPath("cinemaforyou", "video/" + screenId + "_" + instanceSeq);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            LOGGER.info("[CinemaForYou] 屏幕 {} 纹理已重建 ({}x{}, {} mip)", screenId, w, h, texture.levelCount());
        }

        try {
            // 每级缓冲写入 native 内存后统一提交 GPU（三线性采样会用到各级）
            int mips = Math.min(texture.levelCount(), chain.length);
            for (int k = 0; k < mips; k++) {
                NativeImage img = texture.level(k);
                int lw = img.getWidth();
                int lh = img.getHeight();
                writePixelsToNativeImage(img, chain[k], lw, lh);
            }
            texture.uploadAll();
            lastUploadAtMs = System.currentTimeMillis();
            // 首帧（或重开片段后的首帧）已上屏：墙钟基准锚到此刻、从片段起点开始计时
            //（冻结期间不计时，这里必须重锚，否则启动等待时长会被一次性计入 → 位置跳变）
            wallFallback = true;
            wallBasePosMs = segmentStartMs;
            wallBaseWallMs = lastUploadAtMs;
            // 解除主时钟冻结，此后按时钟正常推进
            awaitingFirstUpload = false;
            // 时间线汇总：起播/循环重启 → 首帧解码 → 画面上屏，把"延迟几秒"归因到具体阶段
            if (!timelineLogged && timelineT0Ms > 0L && timelineFirstDecodeMs > 0L) {
                timelineLogged = true;
                LOGGER.info("[CinemaForYou] 时间线{}(循环#{}): 重启→解码首帧 {}ms，解码→上屏 {}ms，合计 {}ms（音频门控等待 {}ms）",
                        loopIndex > 0 ? "" : "起播", loopIndex,
                        timelineFirstDecodeMs - timelineT0Ms,
                        lastUploadAtMs - timelineFirstDecodeMs,
                        lastUploadAtMs - timelineT0Ms,
                        audioGateWaitedMs);
            }
        } catch (Throwable t) {
            LOGGER.error("[CinemaForYou] 纹理上传失败", t);
            error = "纹理上传失败: " + t.getMessage();
        } finally {
            synchronized (frameLock) {
                // 上传完成：槽回到空闲，解码线程可继续
                for (int i = 0; i < slotCount; i++) {
                    if (slotPixels != null && slotPixels[i] != null
                            && slotPixels[i][0] == chain[0]) {
                        slotState[i] = SLOT_EMPTY;
                        slotDueMs[i] = 0L;
                        break;
                    }
                }
            }
        }
    }

    private void releaseSlotByPixels(int[] pixels) {
        if (pixels == null) return;
        synchronized (frameLock) {
            for (int i = 0; i < slotCount; i++) {
                if (slotPixels != null && slotPixels[i] != null
                        && slotPixels[i][0] == pixels) {
                    slotState[i] = SLOT_EMPTY;
                    slotDueMs[i] = 0L;
                    break;
                }
            }
        }
    }

    /**
     * 把 ABGR 像素写入 NativeImage 的 native 堆。
     *
     * <p>{@link NativeImage#getPixelsABGR()} 只是拷贝，写回去无效；
     * 这里直接对 {@link NativeImage#getPointer()} 做批量 mem 写入。
     */
    private static void writePixelsToNativeImage(NativeImage image, int[] abgr, int w, int h) {
        if (image == null) {
            throw new IllegalStateException("视频纹理缓冲为 null");
        }
        if (abgr.length < w * h) {
            throw new IllegalStateException("像素缓冲过小: " + abgr.length + " < " + (w * h));
        }
        long ptr = image.getPointer();
        if (ptr == 0L) {
            throw new IllegalStateException("NativeImage 指针为 0");
        }
        IntBuffer dest = MemoryUtil.memIntBuffer(ptr, w * h);
        dest.put(0, abgr, 0, w * h);
    }

    /** 返回当前错误信息（无错误返回 null）。 */
    public String getError() {
        return error;
    }

    /** 是否已播放到结尾（用于结束后重建/重播判断）。 */
    public boolean hasEnded() {
        return videoEofAtMs > 0;
    }

    // ───────────── 播完行为（播放模式） ─────────────

    /**
     * 视频播完后的动作（仅该屏所有者客户端执行一次）：
     * 0=停止；1=循环本片；2=按队列自动播放下一个；3=播完暂停（保留末帧）。
     *
     * <p>例外：内容来自播放申请授权（{@link #fromRequest}）时不执行上述任何模式，
     * 一律发 STOP——一次"接受"只授权一次播放，要继续播放必须由申请者重新发申请
     * （服务端对续播请求还会做兜底拒绝，见 ScreenManager#playInternal）。
     */
    private void handleEndOfVideo() {
        endReported = true;
        // 时间线：EOF 到真正执行"播完动作"（循环/下一集）之间等了多久——
        // 循环模式的延迟主要就出在这一段（等音频收尾，最长 EOF_MAX_WAIT_MS）
        if (videoEofAtMs > 0) {
            LOGGER.info("[CinemaForYou] 播完处理: EOF 后等待 {}ms 再执行播完动作（循环/下一集/停止）",
                    System.currentTimeMillis() - videoEofAtMs);
        }
        CinemaScreen sc = screen;
        LocalPlayer player = Minecraft.getInstance().player;
        if (sc == null || player == null) return;
        if (!sc.ownerId().equals(player.getUUID().toString())) {
            return; // 非所有者不做自动操作；若所有者循环播放，服务端会重新广播 PLAYING
        }
        // 申请授权内容：不循环、不自动连播，直接停止（屏幕回到停止态，画面清空）。
        // 与既有模式 0"播完停止"语义一致；要继续播放须由申请者重新发播放申请。
        if (fromRequest) {
            LOGGER.info("[CinemaForYou] 屏幕 {} 申请授权内容播完（一次接受只授权一次播放），停止不续播",
                    screenId);
            ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
            return;
        }
        var cfg = com.cinemaforyou.CinemaForYouClient.clientConfig;
        if (cfg == null) {
            ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
            return;
        }
        int mode = cfg.playModeFor(screenId.toString());
        switch (mode) {
            case 1 -> { // 循环本片
                if (!sourceUrl.isEmpty()) {
                    ClientNetworkHandlers.sendAction(
                            ScreenActionPayload.play(screenId, sourceUrl));
                } else {
                    ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
                }
            }
            case 2 -> { // 自动播放下一个（服务端队列，客户端读只读镜像）
                java.util.List<QueueEntry> queue = QueueClient.entriesFor(screenId);
                if (queue.isEmpty()) {
                    ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
                    return;
                }
                int idx = -1;
                for (int i = 0; i < queue.size(); i++) {
                    if (queue.get(i).url().equals(sourceUrl)) {
                        idx = i;
                        break;
                    }
                }
                String next = queue.get((idx + 1) % queue.size()).url();
                ClientNetworkHandlers.sendAction(ScreenActionPayload.play(screenId, next));
            }
            case 3 -> { // 播完暂停：保留末帧，控制界面点"播放"会从头重播
                ClientNetworkHandlers.sendAction(ScreenActionPayload.pause(screenId));
            }
            default -> ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
        }
    }

    /** 返回错误是否已报告给玩家。 */
    public boolean isErrorReported() {
        return errorReported;
    }

    /** 标记错误已报告。 */
    public void markErrorReported() {
        errorReported = true;
    }

    private static int effectiveDecodeHeight(CinemaScreen screen) {
        int requested = screen != null ? screen.resolutionHeight() : 720;
        if (requested <= 0) return 720;
        return Math.min(requested, 1080);
    }

    // #region debug-point B:helper
    private static void debugPoint(String hypothesisId, String location, String msg, Object... kvPairs) {
        DebugLog.debugPoint("sync-rewrite", hypothesisId, location, msg, kvPairs);
    }

    private static String trimForLog(String value) {
        if (value == null) return "";
        return value.length() <= 240 ? value : value.substring(0, 240) + "...";
    }
    // #endregion
}
