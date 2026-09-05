package com.cinemaforyou.client.audio;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.data.CinemaScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 位置音频播放器 + 音视频同步主时钟。
 *
 * <p>使用独立 FFmpegFrameGrabber 解音频（支持 yt-dlp 解析出的独立音频直链），
 * 再通过 Java Sound 输出立体声 PCM。声道左右平衡会根据玩家相对屏幕方位实时更新，
 * 距离越远音量越低。
 *
 * <p>作为主时钟：{@link #getPositionMs()} 返回<strong>声卡实际已播出</strong>的媒体位置
 * （写出的字节数减去仍滞留在声卡缓冲中的内容），视频解码线程据此决定何时呈现帧。
 * 写入采用"每次只写 available() 允许的量、空闲即睡"的非阻塞节奏：
 * <ul>
 *   <li>不会出现大块写入长时间阻塞解码线程（旧实现单次 write 可阻塞 60-80ms）；</li>
 *   <li>位置按每次循环刷新，量化粒度 ≈ 几毫秒，供视频精确对时。</li>
 * </ul>
 *
 * <p>EOF 语义：音频流到结尾后停止解码，进入排空阶段等声卡缓冲播完再标记
 * {@link #finished}，绝不回绕到 0 重播（旧实现的 {@code setTimestamp(0)} 回绕
 * 正是音画错乱、声音反复的来源之一）。
 */
@Environment(EnvType.CLIENT)
public class AudioPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("CinemaForYou/Audio");

    /** 攒够多少字节再交给声卡（约 21ms@48kHz 立体声 16bit，兼顾延迟与吞吐）。 */
    private static final int WRITE_CHUNK_BYTES = 4096;
    /** EOF 后等待声卡缓冲排空的最长时间。 */
    private static final long DRAIN_TIMEOUT_MS = 3000L;

    private final UUID screenId;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile CinemaScreen screen;
    private volatile long pendingSeekMs = -1L;
    private volatile float leftGain = 1.0f;
    private volatile float rightGain = 1.0f;
    private volatile boolean started = false;
    private volatile boolean liveAudio = false;   // 已真正写出过音频数据
    private volatile boolean finished = false;    // 已播完（含排空）
    /** 主时钟：声卡已播出的媒体位置（毫秒）。 */
    private volatile long positionMs = 0L;
    /** 结束（排空完成）时的最终位置。 */
    private volatile long endPositionMs = 0L;

    private final String resolvedUrl;
    private final String sourceUrl;

    private Thread decodeThread;
    private FFmpegFrameGrabber grabber;
    private SourceDataLine line;

    // 以下仅在解码线程内访问
    private long segmentStartMs;      // 当前片段起始媒体位置
    private long writtenMsInSegment;  // 本片段已写入声卡的毫秒数（累计）
    private long totalWrittenBytes;   // 本片段累计写入字节
    private int bytesPerMs = 192;     // 采样率*4/1000，line 打开后重算
    private int sampleRate = 48000;   // 实际采样率（打开后重算）
    private long framesAtSegStart = 0L; // 片段开始时声卡已播帧数（硬件时钟基准）
    private byte[] pending = new byte[WRITE_CHUNK_BYTES * 8];
    private int pendingLen = 0;

    public AudioPlayer(UUID screenId, CinemaScreen screen, String resolvedUrl, String sourceUrl) {
        this.screenId = screenId;
        this.screen = screen;
        this.resolvedUrl = resolvedUrl;
        this.sourceUrl = sourceUrl;
    }

    /** 启动音频流播放。 */
    public void start(long startPosMs) {
        if (running.get()) return;
        running.set(true);
        paused.set(false);
        segmentStartMs = Math.max(0L, startPosMs);
        positionMs = segmentStartMs;
        decodeThread = new Thread(() -> decodeLoop(startPosMs), "CinemaForYou-Audio-" + screenId);
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    /** 暂停。 */
    public void pause() {
        paused.set(true);
        SourceDataLine l = line;
        if (l != null) {
            l.stop();
        }
    }

    /** 恢复。 */
    public void resume() {
        paused.set(false);
        SourceDataLine l = line;
        if (l != null) {
            l.start();
        }
    }

    /** 请求跳转到指定毫秒。 */
    public void seek(long positionMs) {
        pendingSeekMs = Math.max(0L, positionMs);
    }

    /**
     * 停止并释放（非阻塞、线程安全）。
     *
     * <p>解码线程若阻塞在原生网络读取中（interrupt 无法打断），这里只做很短的
     * join，绝不跨线程释放 grabber/声卡——原生对象统一交给解码线程自己的
     * finally 清理，避免并发释放导致的死锁/卡死。
     */
    public void stop() {
        running.set(false);
        Thread t = decodeThread;
        if (t != null) {
            t.interrupt();
            try { t.join(150); } catch (InterruptedException ignored) {}
        }
        if (t == null || !t.isAlive()) {
            closeLine();
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
                try { grabber.release(); } catch (Exception ignored) {}
                grabber = null;
            }
        }
    }

    public boolean isPlaying() {
        return running.get() && !paused.get();
    }

    public boolean hasStarted() {
        return started;
    }

    /** 是否已真正出声（可作为音视频同步主时钟）。 */
    public boolean hasLiveAudio() {
        return liveAudio;
    }

    /** 是否已播放完毕（EOF + 声卡缓冲排空，或音频流不存在/失败）。 */
    public boolean isFinished() {
        return finished;
    }

    /** 主时钟：声卡当前正在播出的媒体位置（毫秒）。 */
    public long getPositionMs() {
        return positionMs;
    }

    /** 播放结束（排空完成）时的最终位置，供视频在音频结束后无缝切换墙钟。 */
    public long getEndPositionMs() {
        return endPositionMs;
    }

    public void updateScreen(CinemaScreen newScreen) {
        this.screen = newScreen;
    }

    /** 每帧更新一次空间声像（距离衰减 + 软声像 + 隔音）。 */
    public void tickSpatial() {
        LocalPlayer player = Minecraft.getInstance().player;
        CinemaScreen current = screen;
        if (player == null || current == null) return;

        var cfg = CinemaForYouClient.clientConfig;
        net.minecraft.world.phys.Vec3 anchor = current.audioAnchor();
        double dx = anchor.x - player.getX();
        double dy = anchor.y - player.getY();
        double dz = anchor.z - player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 传播范围：屏幕自设优先，否则全局默认
        int range = current.audioRangeBlocks() > 0
                ? current.audioRangeBlocks()
                : (cfg != null ? cfg.audioMaxDistance : 128);
        // 距离衰减指数：屏幕自设（tenths/10）优先，否则全局默认
        double exponent = current.audioFalloffTenths() > 0
                ? current.audioFalloffTenths() / 10.0
                : (cfg != null ? cfg.audioFalloffExponent : 1.0);
        double distGain;
        if (dist >= range) {
            distGain = 0.0;
        } else {
            double t = dist / range; // 0=贴脸, 1=最远
            distGain = Math.pow(Math.max(0.0, 1.0 - t), Math.max(0.05, exponent));
        }
        float baseGain = (float) distGain
                * (cfg != null ? cfg.volumeFloat() : 1.0f)
                * (current.volumePercent() / 100.0f);

        // 软声像：pan ∈ [-1,1]（-1=屏幕在左）。压到 ±0.55 保留中心感，
        // 再加 35% 串音，避免"另一只耳朵被堵住"的极端偏置。
        float yaw = (float) Math.toRadians(player.getYRot());
        double rightX = Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double rawPan = horizontal < 0.001 ? 0.0 : ((dx * rightX) + (dz * rightZ)) / horizontal;
        float p = (float) Math.max(-1.0, Math.min(1.0, rawPan)) * 0.55f;
        float l = (1.0f - p) * 0.5f;
        float r = (1.0f + p) * 0.5f;
        final float crossfeed = 0.35f;
        l += (0.5f - l) * crossfeed;
        r += (0.5f - r) * crossfeed;

        // 隔音：屏幕与听者之间有实心方块挡住时完全消音（密闭房间外听不到）
        if (isOccluded(player, current)) {
            l = 0.0f;
            r = 0.0f;
        }

        leftGain = baseGain * l;
        rightGain = baseGain * r;
        applyMasterGain(1.0f);
    }

    // ───────────── 隔音（视线遮挡） ─────────────

    private net.minecraft.core.BlockPos lastOcclEye;
    private long lastOcclMs = 0L;
    private boolean occluded = false;

    private boolean isOccluded(LocalPlayer player, CinemaScreen current) {
        net.minecraft.core.BlockPos eyeBlock = player.blockPosition();
        long now = System.currentTimeMillis();
        if (lastOcclEye == null || !lastOcclEye.equals(eyeBlock) || now - lastOcclMs > 300) {
            lastOcclEye = eyeBlock;
            lastOcclMs = now;
            var level = Minecraft.getInstance().level;
            occluded = level != null
                    && rayHitsSolid(level, player.getEyePosition(), current.audioOcclusionPoint());
        }
        return occluded;
    }

    /** 3D-DDA 体素射线：路径上遇到任何实心方块返回 true（完全隔音）。 */
    private static boolean rayHitsSolid(
            net.minecraft.world.level.Level level,
            net.minecraft.world.phys.Vec3 from,
            net.minecraft.world.phys.Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double maxDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (maxDist <= 0.5) return false;
        double dirX = dx / maxDist, dirY = dy / maxDist, dirZ = dz / maxDist;

        int x = (int) Math.floor(from.x);
        int y = (int) Math.floor(from.y);
        int z = (int) Math.floor(from.z);

        int stepX = dirX > 0 ? 1 : -1;
        int stepY = dirY > 0 ? 1 : -1;
        int stepZ = dirZ > 0 ? 1 : -1;

        double tDeltaX = dirX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirX);
        double tDeltaY = dirY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirY);
        double tDeltaZ = dirZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dirZ);

        double tMaxX = dirX == 0 ? Double.POSITIVE_INFINITY
                : (dirX > 0 ? (x + 1 - from.x) : (from.x - x)) * tDeltaX;
        double tMaxY = dirY == 0 ? Double.POSITIVE_INFINITY
                : (dirY > 0 ? (y + 1 - from.y) : (from.y - y)) * tDeltaY;
        double tMaxZ = dirZ == 0 ? Double.POSITIVE_INFINITY
                : (dirZ > 0 ? (z + 1 - from.z) : (from.z - z)) * tDeltaZ;

        for (int i = 0; i < 1024; i++) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                double t = tMaxX;
                tMaxX += tDeltaX;
                if (t > maxDist) break;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                double t = tMaxY;
                tMaxY += tDeltaY;
                if (t > maxDist) break;
            } else {
                z += stepZ;
                double t = tMaxZ;
                tMaxZ += tDeltaZ;
                if (t > maxDist) break;
            }
            var state = level.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
            if (!state.isAir() && state.isSolid()) {
                return true;
            }
        }
        return false;
    }

    private void decodeLoop(long startPosMs) {
        try {
            grabber = new FFmpegFrameGrabber(resolvedUrl);
            String headers = com.cinemaforyou.client.video.UrlResolver.ffmpegHttpHeaders(
                    sourceUrl, resolvedUrl);
            if (headers != null) {
                grabber.setOption("headers", headers);
                // 复用同一 TCP 连接发后续 Range 请求（远程隧道下每次建连都很慢）
                grabber.setOption("http_persistent", "1");
                // TCP 建连超时（µs）：服务器不可达/被防火墙丢弃时快速失败而非无限挂起
                grabber.setOption("timeout", "8000000");
                // 网络读取超时（µs）：防止解码线程无限阻塞在原生 read 上导致
                // 停止/重播时线程堆积乃至卡死
                grabber.setOption("rw_timeout", "15000000");
            }
            grabber.setSampleFormat(avutil.AV_SAMPLE_FMT_S16);
            grabber.start();
            started = true;

            if (!grabber.hasAudio()) {
                LOGGER.info("[CinemaForYou] 音频源不含音频流，静音播放: {}",
                        trimForLog(resolvedUrl));
                finished = true;
                endPositionMs = segmentStartMs;
                return;
            }

            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48000;
            AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            // 声卡缓冲约 500ms：吸收解码/网络抖动，位置计算会扣掉滞留缓冲，
            // 因此不会引入额外延迟误差
            int outputBufferSize = Math.max(16384, sampleRate * format.getFrameSize() / 2);
            line.open(format, outputBufferSize);
            this.sampleRate = sampleRate;
            bytesPerMs = Math.max(1, sampleRate * format.getFrameSize() / 1000);
            line.start();
            LOGGER.info("[CinemaForYou] 音频输出已打开 {}Hz ch={} buf={}B",
                    sampleRate, grabber.getAudioChannels(), line.getBufferSize());

            if (startPosMs > 0) {
                try {
                    grabber.setTimestamp(startPosMs * 1000L);
                } catch (Exception e) {
                    LOGGER.warn("[CinemaForYou] 音频初始 seek 失败（流不支持）: {}", e.toString());
                }
            }
            segmentStartMs = Math.max(0L, startPosMs);
            writtenMsInSegment = 0L;
            totalWrittenBytes = 0L;
            pendingLen = 0;
            framesAtSegStart = line.getLongFramePosition();
            positionMs = segmentStartMs;

            boolean eof = false;
            long drainDeadlineMs = 0L;
            while (running.get()) {
                if (paused.get()) {
                    // 暂停中也处理挂起的 seek（控制界面"暂停中跳转"的场景）
                    if (pendingSeekMs >= 0) {
                        applySeek(pendingSeekMs);
                    }
                    Thread.sleep(20);
                    continue;
                }
                if (pendingSeekMs >= 0) {
                    applySeek(pendingSeekMs);
                }

                if (pendingLen >= WRITE_CHUNK_BYTES) {
                    writePending();
                    if (pendingLen >= WRITE_CHUNK_BYTES) {
                        // 声卡缓冲仍满：等它消化一点再继续解码，避免无界超前
                        updatePosition();
                        Thread.sleep(5);
                        continue;
                    }
                }

                if (eof) {
                    // EOF：先把残余数据冲完，再等声卡缓冲播完，绝不回绕
                    writePending();
                    updatePosition();
                    if (pendingLen == 0 && bufferedMs() <= 2L) {
                        break;
                    }
                    if (System.currentTimeMillis() > drainDeadlineMs) {
                        break;
                    }
                    Thread.sleep(10);
                    continue;
                }

                Frame frame = grabber.grabSamples();
                if (frame == null) {
                    // 音频流 EOF：进入排空阶段
                    eof = true;
                    drainDeadlineMs = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
                    LOGGER.info("[CinemaForYou] 音频流到结尾，排空声卡缓冲: pos≈{}ms", positionMs);
                    continue;
                }
                if (frame.samples == null || frame.samples.length == 0) {
                    continue;
                }

                byte[] pcm = convertToStereoPcm(frame,
                        Math.max(1, grabber.getAudioChannels()), leftGain, rightGain);
                if (pcm.length > 0 && line != null) {
                    appendPending(pcm);
                    liveAudio = true;
                }
                updatePosition();
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            LOGGER.warn("[CinemaForYou] 音频播放失败: {}", e.toString());
        } finally {
            finished = true;
            closeLine();
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
                try { grabber.release(); } catch (Exception ignored) {}
                grabber = null;
            }
            running.set(false);
            endPositionMs = positionMs;
        }
    }

    /** 处理 seek：声卡缓冲清空、字节计数归零、时钟锚到新位置。 */
    private void applySeek(long targetMs) {
        pendingSeekMs = -1L;
        try {
            grabber.setTimestamp(targetMs * 1000L);
        } catch (Exception e) {
            LOGGER.warn("[CinemaForYou] 音频 seek 到 {}ms 失败: {}", targetMs, e.toString());
        }
        SourceDataLine l = line;
        if (l != null) {
            l.flush();
            framesAtSegStart = l.getLongFramePosition();
        }
        segmentStartMs = Math.max(0L, targetMs);
        writtenMsInSegment = 0L;
        totalWrittenBytes = 0L;
        pendingLen = 0;
        positionMs = segmentStartMs;
    }

    private void appendPending(byte[] pcm) {
        if (pending.length - pendingLen < pcm.length) {
            byte[] grown = new byte[Math.max(pending.length * 2, pendingLen + pcm.length)];
            System.arraycopy(pending, 0, grown, 0, pendingLen);
            pending = grown;
        }
        System.arraycopy(pcm, 0, pending, pendingLen, pcm.length);
        pendingLen += pcm.length;
    }

    /** 非阻塞写出（每次最多写声卡当前可用空间），写不完留在 pending 下轮继续。 */
    private void writePending() {
        SourceDataLine l = line;
        if (l == null || pendingLen == 0) return;
        int free = l.available();
        if (free <= 0) return;
        int toWrite = Math.min(pendingLen, free);
        int written = l.write(pending, 0, toWrite);
        if (written > 0) {
            if (written < pendingLen) {
                System.arraycopy(pending, written, pending, 0, pendingLen - written);
            }
            pendingLen -= written;
            totalWrittenBytes += written;
            writtenMsInSegment = totalWrittenBytes / bytesPerMs;
        }
    }

    /**
     * 更新主时钟位置。优先用声卡硬件已播帧数（getLongFramePosition）——
     * 它反映真正离开混音器的位置，不依赖 available() 估算，长时间播放不会漂移；
     * 个别驱动返回 -1 时回退到"已写字节 − 滞留缓冲"估算。
     */
    private void updatePosition() {
        SourceDataLine l = line;
        if (l == null || !started) return;
        long pos;
        long playedFrames = l.getLongFramePosition();
        if (playedFrames >= 0) {
            pos = segmentStartMs
                    + (playedFrames - framesAtSegStart) * 1000L / Math.max(1, sampleRate);
        } else {
            pos = segmentStartMs + writtenMsInSegment - bufferedMs();
        }
        positionMs = Math.max(0L, Math.max(segmentStartMs, pos));
    }

    private long bufferedMs() {
        SourceDataLine l = line;
        if (l == null) return 0L;
        int bufferSize = Math.max(1, l.getBufferSize());
        int free = Math.max(0, Math.min(bufferSize, l.available()));
        return (long) (bufferSize - free) / bytesPerMs;
    }

    private byte[] convertToStereoPcm(Frame frame, int inputChannels,
                                      float currentLeftGain, float currentRightGain) {
        Buffer[] samples = frame.samples;
        if (samples == null || samples.length == 0) return new byte[0];

        if (samples[0] instanceof ShortBuffer) {
            return convertShortSamples(samples, Math.max(1, inputChannels), currentLeftGain, currentRightGain);
        }
        if (samples[0] instanceof FloatBuffer) {
            return convertFloatSamples(samples, Math.max(1, inputChannels), currentLeftGain, currentRightGain);
        }
        if (samples[0] instanceof ByteBuffer buffer) {
            ByteBuffer dup = buffer.duplicate();
            byte[] out = new byte[dup.remaining()];
            dup.get(out);
            return out;
        }
        return new byte[0];
    }

    private byte[] convertShortSamples(Buffer[] samples, int inputChannels,
                                       float currentLeftGain, float currentRightGain) {
        if (samples.length == 1) {
            ShortBuffer sb = ((ShortBuffer) samples[0]).duplicate();
            if (inputChannels <= 1) {
                int count = sb.remaining();
                byte[] out = new byte[count * 4];
                for (int i = 0; i < count; i++) {
                    short mono = sb.get();
                    writeShort(out, i * 4, scale(mono, currentLeftGain));
                    writeShort(out, i * 4 + 2, scale(mono, currentRightGain));
                }
                return out;
            }

            int frames = sb.remaining() / inputChannels;
            byte[] out = new byte[frames * 4];
            for (int i = 0; i < frames; i++) {
                short left = sb.get();
                short right = inputChannels > 1 ? sb.get() : left;
                for (int ch = 2; ch < inputChannels && sb.hasRemaining(); ch++) {
                    sb.get();
                }
                writeShort(out, i * 4, scale(left, currentLeftGain));
                writeShort(out, i * 4 + 2, scale(right, currentRightGain));
            }
            return out;
        }

        ShortBuffer leftBuf = ((ShortBuffer) samples[0]).duplicate();
        ShortBuffer rightBuf = ((ShortBuffer) samples[Math.min(1, samples.length - 1)]).duplicate();
        int count = Math.min(leftBuf.remaining(), rightBuf.remaining());
        byte[] out = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            short left = leftBuf.get();
            short right = inputChannels > 1 ? rightBuf.get() : left;
            writeShort(out, i * 4, scale(left, currentLeftGain));
            writeShort(out, i * 4 + 2, scale(right, currentRightGain));
        }
        return out;
    }

    private byte[] convertFloatSamples(Buffer[] samples, int inputChannels,
                                       float currentLeftGain, float currentRightGain) {
        FloatBuffer leftBuf = ((FloatBuffer) samples[0]).duplicate();
        FloatBuffer rightBuf = ((FloatBuffer) samples[Math.min(1, samples.length - 1)]).duplicate();
        int count = samples.length == 1 && inputChannels > 1
                ? leftBuf.remaining() / inputChannels
                : Math.min(leftBuf.remaining(), rightBuf.remaining());
        byte[] out = new byte[count * 4];

        if (samples.length == 1 && inputChannels > 1) {
            for (int i = 0; i < count; i++) {
                float left = leftBuf.get();
                float right = leftBuf.get();
                for (int ch = 2; ch < inputChannels && leftBuf.hasRemaining(); ch++) {
                    leftBuf.get();
                }
                writeShort(out, i * 4, scale(left, currentLeftGain));
                writeShort(out, i * 4 + 2, scale(right, currentRightGain));
            }
            return out;
        }

        for (int i = 0; i < count; i++) {
            float left = leftBuf.get();
            float right = inputChannels > 1 ? rightBuf.get() : left;
            writeShort(out, i * 4, scale(left, currentLeftGain));
            writeShort(out, i * 4 + 2, scale(right, currentRightGain));
        }
        return out;
    }

    private void applyMasterGain(float gain) {
        SourceDataLine l = line;
        if (l == null || !l.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl ctrl = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
        float clamped = Math.max(0.0001f, Math.min(1.0f, gain));
        float db = (float) (20.0 * Math.log10(clamped));
        db = Math.max(ctrl.getMinimum(), Math.min(ctrl.getMaximum(), db));
        ctrl.setValue(db);
    }

    private void closeLine() {
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try { l.stop(); } catch (Exception ignored) {}
            try { l.flush(); } catch (Exception ignored) {}
            try { l.close(); } catch (Exception ignored) {}
        }
    }

    private short scale(short sample, float gain) {
        return (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
    }

    private short scale(float sample, float gain) {
        return (short) Math.max(Short.MIN_VALUE,
                Math.min(Short.MAX_VALUE, Math.round(sample * gain * Short.MAX_VALUE)));
    }

    private void writeShort(byte[] out, int offset, short value) {
        out[offset] = (byte) (value & 0xFF);
        out[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static String trimForLog(String value) {
        if (value == null) return "";
        return value.length() <= 240 ? value : value.substring(0, 240) + "...";
    }
}
