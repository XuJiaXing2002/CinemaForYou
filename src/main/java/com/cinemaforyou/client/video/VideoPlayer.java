package com.cinemaforyou.client.video;

import com.cinemaforyou.client.audio.AudioPlayer;
import com.cinemaforyou.client.network.ClientNetworkHandlers;
import com.cinemaforyou.client.render.VideoFrameTexture;
import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.network.ScreenActionPayload;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
    private static final Path DEBUG_LOG =
            Path.of("d:/Minecraft_Project/CinemaForYou/.dbg/trae-debug-log-video-link-stutter.ndjson");

    /** 帧槽数量（解码可领先的帧数；越小延迟越低，越大抗网络抖动越强）。 */
    private static final int SLOT_COUNT = 3;
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

    private final UUID screenId;
    private volatile CinemaScreen screen;
    private final String sourceUrl;
    private volatile long durationMs = 0L;

    private FFmpegFrameGrabber grabber; // 注意：解码线程写、主线程可能在 release() 读，见 release()
    private volatile AudioPlayer audioPlayer;
    private Thread decodeThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);

    // ───────────── 帧槽（解码线程写、渲染线程读，frameLock 保护） ─────────────
    private final Object frameLock = new Object();
    // 每个槽 = 一条 mip 链：slotPixels[槽][0] 为原始帧，[1..] 为逐级减半的 mip 层
    private int[][][] slotPixels = null;
    private final long[] slotDueMs = new long[SLOT_COUNT];
    private final int[] slotState = new int[SLOT_COUNT];
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
    /** 解码线程置位：请求重开解码流（seek 失败/长时间滚不到目标时）。 */
    private volatile boolean reopenRequested = false;
    /** 最近一次成功出新帧的时刻（解码线程写、渲染线程读，毫秒）。 */
    private volatile long lastFrameAtMs = 0L;
    /** 渲染线程看门狗：已触发过补 seek（未恢复则升级为重开）。 */
    private volatile boolean resyncArmed = false;
    private volatile long resyncArmedAtMs = 0L;
    /** 看门狗强制重开的最小间隔（防重开风暴）。 */
    private volatile long lastForcedActionAtMs = 0L;
    /** 音频门控：会话创建后等它真正出声的最长时间；超时画面先行（音频掉线时防永久黑屏）。 */
    private static final long AUDIO_GATE_MAX_MS = 3500L;
    /** 音频门控开始等待的时刻（0 = 未在等）。 */
    private volatile long audioGateStartMs = 0L;
    private volatile boolean audioGateLogged = false;
    /** 最近一次成功 seek/重开的时刻（毫秒）：之后短暂出现的空帧不算 EOF。 */
    private volatile long lastSeekHandledAtMs = 0L;
    /** 连续空帧计数（seek 后与正常播放共用，见 decodeLoop）。 */
    private int consecutiveNulls = 0;
    /** seek 落点超前时的后退重试次数。 */
    private int seekFixTries = 0;
    /** 最近一次成功上传显示帧的时刻（渲染线程写，冻结看门狗用）。 */
    private volatile long lastUploadAtMs = 0L;

    // decode 线程私有状态（无需 volatile）
    private long firstPtsUs = -1L;       // 片段首帧 PTS（µs），仅作音频启动/日志标记
    private long lastPtsUs = -1L;        // 上一帧 PTS，用于单调化
    private boolean wallFallback;        // true = 当前用墙钟而不是音频时钟
    private long wallBasePosMs;          // 墙钟回退的起点媒体位置
    private long wallBaseWallMs;         // 墙钟回退的起点墙钟
    private long segmentStartMs;         // 当前片段起始媒体位置（seek 后重置）

    public VideoPlayer(UUID screenId, CinemaScreen screen, String sourceUrl) {
        this.screenId = screenId;
        this.screen = screen;
        this.sourceUrl = sourceUrl;
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
    }

    public String getSourceUrl() {
        return sourceUrl;
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
        running.set(false);
        if (decodeThread != null) {
            decodeThread.interrupt();
            // 只短暂等待：解码线程若阻塞在原生读取中会自行在 finally 清理，
            // 绝不让主线程长时间 join 或跨线程释放原生 grabber（防卡死）
            try { decodeThread.join(200); } catch (InterruptedException ignored) {}
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

    private void releaseTexture() {
        if (texture != null) {
            texture.close();
            texture = null;
        }
        if (textureId != null) {
            Minecraft.getInstance().getTextureManager().release(textureId);
            textureId = null;
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
            grabber = openConfiguredGrabber(videoResolvedUrl);
            long startMs = System.currentTimeMillis();
            grabber.start();
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
                if (!safeSeek(startPosMs)) {
                    // 流不支持 seek：从头开始播
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
                    // 确认是真正的视频流结束
                    consecutiveNulls = 0;
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
                    if (firstMs > segmentStartMs + 400L && seekFixTries < 6) {
                        long[] backOffsets = {-800L, -2000L, -5000L, -12000L, -30000L, -60000L};
                        long backTarget = Math.max(0L,
                                segmentStartMs + backOffsets[Math.min(seekFixTries, backOffsets.length - 1)]);
                        seekFixTries++;
                        LOGGER.warn("[CinemaForYou] seek 落点超前：画面 {}ms 音频 {}ms，"
                                        + "向后退到 {}ms 重试",
                                firstMs, segmentStartMs, backTarget);
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
        }
    }

    /** 启动音频会话（幂等：只创建一次）。音频流若不存在会很快自我结束。 */
    private void startAudioIfNeeded(UrlResolver.ResolvedSource resolved) {
        if (audioPlayer != null) return;
        if (!running.get()) return; // release() 已介入：不要再开新会话
        AudioPlayer ap = new AudioPlayer(screenId, screen, resolved.audioUrl(), sourceUrl);
        audioPlayer = ap;
        ap.start(segmentStartMs);
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

    /** 处理一次 seek：重置 PTS 映射、清空排队帧、重锚时钟基准。 */
    private void applySeek(long targetMs) {
        pendingSeekMs = -1L;
        if (!safeSeek(targetMs)) {
            // 本次 seek 失败：标记重开解码流（reopen 时会再次尝试定位）
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
        resyncArmed = false;
    }

    // ───────────── 解码流打开 / 重开 ─────────────

    /** 创建并配置好抓帧器（未 start）。同一配置在重开解码流时复用。 */
    private FFmpegFrameGrabber openConfiguredGrabber(String url) throws Exception {
        FFmpegFrameGrabber g = new FFmpegFrameGrabber(url);
        g.setOption("rtsp_transport", "tcp");
        // 强制 BGR24，解码线程直接按 B/G/R 字节序手工转 ABGR
        g.setPixelFormat(avutil.AV_PIX_FMT_BGR24);
        int decodeHeight = effectiveDecodeHeight(screen);
        if (decodeHeight > 0) {
            // 只缩小不放大：高度取 min(decodeHeight, ih)，宽度 -2 自动取偶数
            g.setVideoOption("vf", "scale=-2:min(" + decodeHeight + ",ih)");
        }
        String headers = UrlResolver.ffmpegHttpHeaders(sourceUrl, url);
        if (headers != null) {
            g.setOption("headers", headers);
            // 复用同一 TCP 连接发后续 Range 请求：FFmpeg 默认每个请求新建连接，
            // 文件探测/跳读会产生大量小请求，经远程隧道时每次建连都很慢
            g.setOption("http_persistent", "1");
            // TCP 建连超时（µs）：服务器不可达/被防火墙丢弃时快速失败而非无限挂起
            g.setOption("timeout", "8000000");
            // 网络读取超时（µs）：防止原生 read 无限阻塞（停止/重播时线程堆积）
            g.setOption("rw_timeout", "15000000");
        }
        return g;
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
                try { old.stop(); } catch (Exception ignored) {}
                try { old.release(); } catch (Exception ignored) {}
            }
            if (videoResolvedUrl == null) return;
            FFmpegFrameGrabber g = openConfiguredGrabber(videoResolvedUrl);
            g.start();
            long target = Math.max(0L, segmentStartMs);
            boolean ok = seekGrabber(g, target);
            grabber = g;
            lastSeekHandledAtMs = System.currentTimeMillis();
            if (!ok) {
                LOGGER.warn("[CinemaForYou] 重开后定位 {}ms 失败，从头/当前位置播放", target);
            }
            resetSlots();
            firstPtsUs = -1L;
            lastPtsUs = -1L;
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
            masterPosMs = wallBasePosMs + (nowWall - wallBaseWallMs);
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
                for (int i = 0; i < SLOT_COUNT; i++) {
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
            for (int i = 0; i < SLOT_COUNT; i++) {
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
                for (int i = 0; i < SLOT_COUNT; i++) {
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
                    for (int i = 0; i < SLOT_COUNT; i++) {
                        if (slotState[i] == SLOT_EMPTY) {
                            slotState[i] = SLOT_DECODING;
                            dest = slotPixels[i][0];
                            break;
                        }
                    }
                } else if (!uploading) {
                    // 此刻没有上传进行中，安全整体重分配（每槽一条 mip 链）
                    int[][][] fresh = new int[SLOT_COUNT][mipCount][];
                    for (int i = 0; i < SLOT_COUNT; i++) {
                        for (int k = 0; k < mipCount; k++) {
                            fresh[i][k] = new int[
                                    Math.max(1, w >> k) * Math.max(1, h >> k)];
                        }
                    }
                    slotPixels = fresh;
                    frameWidth = w;
                    frameHeight = h;
                    for (int i = 0; i < SLOT_COUNT; i++) {
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
        return true;
    }

    private int idxOf(int[] pixels) {
        for (int i = 0; i < SLOT_COUNT; i++) {
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
     * BGR24（或 BGRA）ByteBuffer 手工转 ABGR int 数组。
     * 像素行间可能有对齐填充，必须按 {@link Frame#imageStride} 逐行拷贝。
     */
    private boolean convertFrameToAbgr(Frame frame, int[] dest, int w, int h) {
        Buffer[] planes = frame.image;
        if (planes == null || planes.length == 0) return false;
        Buffer p0 = planes[0];
        if (!(p0 instanceof ByteBuffer srcRaw)) return false;
        if (frame.imageDepth != Frame.DEPTH_UBYTE) return false;

        ByteBuffer src = srcRaw.duplicate();
        int stride = frame.imageStride > 0 ? frame.imageStride : w * 3;
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
                if (bpp == 4) src.get(); // 跳过 alpha
                dest[idx + x] = 0xFF000000 | r | (g << 8) | (bl << 16);
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
            if (nowGate - audioGateStartMs < AUDIO_GATE_MAX_MS) {
                return;
            }
            if (!audioGateLogged) {
                audioGateLogged = true;
                LOGGER.warn("[CinemaForYou] 音频 {}ms 仍未出声，画面先行呈现（音频可能无响应）",
                        AUDIO_GATE_MAX_MS);
            }
        } else {
            audioGateStartMs = 0L;
        }

        // ── 冻结看门狗：画面长时间没更新但音频在走（连续快速 seek 卡死场景） ──
        // 判据用"实际显示帧"（lastUploadAtMs），解码线程正常但不显示同样能触发：
        // 第一步补 seek 到当前音频位置；仍不显示则升级为整体重开解码流（带 3s 冷却）。
        if (error == null && videoEofAtMs <= 0 && running.get() && !paused.get()
                && lastUploadAtMs > 0L
                && decodeThread != null && decodeThread.isAlive()) {
            long nowMs = System.currentTimeMillis();
            long idle = nowMs - lastUploadAtMs;
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
            for (int i = 0; i < SLOT_COUNT; i++) {
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
            textureId = Identifier.fromNamespaceAndPath("cinemaforyou", "video/" + screenId);
            Minecraft.getInstance().getTextureManager().register(textureId, texture);
            LOGGER.info("[CinemaForYou] 屏幕 {} 纹理已创建 ({}x{}, {} mip)", screenId, w, h, texture.levelCount());
        } else if (texture.levelCount() != chain.length
                || texture.levelWidth(0) != w || texture.levelHeight(0) != h) {
            releaseTexture();
            texture = new VideoFrameTexture("cinema_" + screenId);
            texture.init(w, h);
            textureId = Identifier.fromNamespaceAndPath("cinemaforyou", "video/" + screenId);
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
        } catch (Throwable t) {
            LOGGER.error("[CinemaForYou] 纹理上传失败", t);
            error = "纹理上传失败: " + t.getMessage();
        } finally {
            synchronized (frameLock) {
                // 上传完成：槽回到空闲，解码线程可继续
                for (int i = 0; i < SLOT_COUNT; i++) {
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
            for (int i = 0; i < SLOT_COUNT; i++) {
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
     */
    private void handleEndOfVideo() {
        endReported = true;
        CinemaScreen sc = screen;
        LocalPlayer player = Minecraft.getInstance().player;
        if (sc == null || player == null) return;
        if (!sc.ownerId().equals(player.getUUID().toString())) {
            return; // 非所有者不做自动操作；若所有者循环播放，服务端会重新广播 PLAYING
        }
        var cfg = com.cinemaforyou.CinemaForYouClient.clientConfig;
        if (cfg == null) {
            ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
            return;
        }
        int mode = cfg.screenPlayMode.getOrDefault(screenId.toString(), 0);
        switch (mode) {
            case 1 -> { // 循环本片
                if (!sourceUrl.isEmpty()) {
                    ClientNetworkHandlers.sendAction(
                            ScreenActionPayload.play(screenId, sourceUrl));
                } else {
                    ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
                }
            }
            case 2 -> { // 自动播放下一个（队列）
                java.util.List<String> queue = cfg.screenPlaylist
                        .getOrDefault(screenId.toString(), java.util.List.of());
                if (queue.isEmpty()) {
                    ClientNetworkHandlers.sendAction(ScreenActionPayload.stop(screenId));
                    return;
                }
                int idx = queue.indexOf(sourceUrl);
                String next = queue.get((idx + 1) % queue.size());
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
        try {
            Files.createDirectories(DEBUG_LOG.getParent());
            StringBuilder json = new StringBuilder();
            json.append("{\"sessionId\":\"video-link-stutter\",\"runId\":\"sync-rewrite\",\"hypothesisId\":\"")
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
