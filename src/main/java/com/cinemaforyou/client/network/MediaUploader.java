package com.cinemaforyou.client.network;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * 客户端"本地视频上传到服务器媒体库"驱动（分块发送）。
 *
 * <p>为什么必须分块：视频文件动辄几百 MB～数 GB，单个自定义包有 1MB 上限，
 * 一次性发送大包会直接断开连接。这里每次客户端 tick 只发送约 512KB
 * （2 × {@link #CHUNK_SIZE}=256KB），逐块等待服务端处理，网络层永远不会被塞爆。
 *
 * <p>流程：{@link #start(File)} 发开始包 → 服务端校验后回 READY（含最终文件名）
 * → 每 tick 读文件发分块 → 发完成包 → 服务端落盘回 SUCCESS。
 * 进度在客户端本地按已发送字节计算，通过 {@link #setListener(Runnable)} 通知界面刷新，
 * 并在 25%/50%/75% 与完成/失败时发聊天栏提示；点「取消上传」或断线都会中止并清理。
 *
 * <p>所有方法都在客户端主线程调用（接收器已切回主线程；tick 由
 * {@code ClientNetworkHandlers} 里的 END_CLIENT_TICK 驱动）。
 */
@Environment(EnvType.CLIENT)
public final class MediaUploader {

    /** 分块大小（服务端上限 512KB，留足余量）。 */
    public static final int CHUNK_SIZE = 256 * 1024;

    /** 每个客户端 tick 最多发送的字节数（约 10MB/s，兼顾速度与连接稳定）。 */
    private static final int BYTES_PER_TICK = 512 * 1024;

    /** 等待服务端 READY / SUCCESS 回执的超时。 */
    private static final long READY_TIMEOUT_MS = 15_000L;
    private static final long FINISH_TIMEOUT_MS = 30_000L;

    /** 允许上传的媒体扩展名（与本地视频库/服务端校验同一套白名单）。 */
    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".avi",
        ".flv", ".wmv", ".ts", ".m4v", ".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac"};

    private static final int STATE_IDLE = 0;
    private static final int STATE_WAIT_READY = 1;
    private static final int STATE_SENDING = 2;
    private static final int STATE_WAIT_FINISH = 3;

    private static final Random RANDOM = new Random();

    private static volatile int state = STATE_IDLE;
    private static volatile Runnable listener;

    private static int uploadId;
    private static File file;
    private static String fileName = "";
    private static String finalName = "";
    private static long totalBytes;
    private static long sentBytes;
    private static long deadlineMs;
    private static InputStream in;
    private static long lastNotifyMs;
    private static int lastNotifyPct = -1;
    private static int lastChatPct = 0;

    private MediaUploader() {}

    // ───────────── 状态查询（供界面显示） ─────────────

    /** 是否有上传正在进行（含等待服务端回执）。 */
    public static boolean isUploading() {
        return state != STATE_IDLE;
    }

    /** 上传进度 0~100（未上传/未知返回 0）。 */
    public static int percent() {
        if (totalBytes <= 0) return 0;
        return (int) Math.min(100, sentBytes * 100 / totalBytes);
    }

    /** 界面/提示用状态文本。 */
    public static String statusText() {
        if (!isUploading()) return "";
        String name = fileName.isEmpty() ? "…" : fileName;
        return switch (state) {
            case STATE_WAIT_READY -> "§7等待服务器确认: " + name;
            case STATE_WAIT_FINISH -> "§7服务器写入中… " + percent() + "%";
            default -> "§e上传中 " + percent() + "% · " + name;
        };
    }

    /** 正在上传的本地文件（无返回 null）。 */
    public static File currentFile() {
        return file;
    }

    /** 服务端确认的最终文件名（重名加序号后的名字；未确认为空）。 */
    public static String finalName() {
        return finalName;
    }

    /** 上传进度变化监听（界面刷新用；上传结束/失败也会触发一次）。 */
    public static void setListener(Runnable onProgress) {
        listener = onProgress;
    }

    public static void clearListener() {
        listener = null;
    }

    private static void notifyListener() {
        Runnable r = listener;
        if (r != null) r.run();
    }

    // ───────────── 启动 / 取消 ─────────────

    /** 校验文件名是否在允许的媒体扩展名白名单内。 */
    public static boolean isSupportedVideoName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 开始上传本地文件。调用方（文件选择回调）需已确认文件存在且扩展名合法；
     * 服务端还会独立校验权限（OP≥2）与扩展名，校验失败会回 ERROR。
     */
    public static void start(File f) {
        if (isUploading()) {
            chat("§c[CinemaForYou] 已有上传任务进行中，请等待完成或先取消");
            return;
        }
        if (f == null || !f.isFile()) {
            chat("§c[CinemaForYou] 文件不存在: " + (f == null ? "?" : f.getName()));
            return;
        }
        if (!isSupportedVideoName(f.getName())) {
            chat("§c[CinemaForYou] 不是支持的视频/音频格式，拒绝上传: " + f.getName());
            return;
        }
        uploadId = RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1;
        file = f;
        fileName = f.getName();
        finalName = "";
        totalBytes = f.length();
        sentBytes = 0;
        lastNotifyPct = -1;
        lastChatPct = 0;
        state = STATE_WAIT_READY;
        deadlineMs = System.currentTimeMillis() + READY_TIMEOUT_MS;
        ClientNetworkHandlers.sendMediaUploadStart(uploadId, fileName, totalBytes);
        chat("§7[CinemaForYou] 准备上传: " + fileName + "（" + sizeText(totalBytes)
                + "），等待服务器确认…");
        notifyListener();
    }

    /** 取消上传（通知服务端清理临时文件）。 */
    public static void cancel() {
        if (!isUploading()) return;
        int id = uploadId;
        boolean hadSession = state != STATE_WAIT_READY;
        reset();
        if (hadSession) {
            ClientNetworkHandlers.sendMediaUploadCancel(id);
        }
        chat("§e[CinemaForYou] 已取消上传（进行中的临时数据已清理）");
        notifyListener();
    }

    /** 断线/退出时复位本地状态（不发包；服务端随 DISCONNECT 自行清理）。 */
    public static void reset() {
        closeQuietly();
        state = STATE_IDLE;
        file = null;
        finalName = "";
        sentBytes = 0;
        totalBytes = 0;
        lastNotifyPct = -1;
    }

    // ───────────── tick 驱动（分块发送） ─────────────

    /** 客户端 tick：持续推进上传（界面关闭也继续，直至完成/失败/取消）。 */
    public static void tick() {
        if (state == STATE_IDLE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) {
            reset();
            chat("§c[CinemaForYou] 与服务器断开连接，上传已中断");
            notifyListener();
            return;
        }
        long now = System.currentTimeMillis();
        if (state == STATE_WAIT_READY || state == STATE_WAIT_FINISH) {
            if (now > deadlineMs) {
                if (state == STATE_WAIT_READY) {
                    int id = uploadId;
                    reset();
                    ClientNetworkHandlers.sendMediaUploadCancel(id);
                    chat("§c[CinemaForYou] 服务器未响应上传请求（可能不支持或版本不符），已中止");
                } else {
                    reset();
                    chat("§c[CinemaForYou] 等待服务器写入回执超时，已中止（请刷新媒体库确认）");
                }
                notifyListener();
            }
            return;
        }
        // STATE_SENDING：按字节预算逐块发送（绝不整文件单包）
        try {
            long budget = BYTES_PER_TICK;
            while (budget > 0 && sentBytes < totalBytes) {
                int want = (int) Math.min(CHUNK_SIZE, Math.min(budget, totalBytes - sentBytes));
                byte[] buf = new byte[want];
                int n = in.readNBytes(buf, 0, want);
                if (n <= 0) {
                    abortLocal("读取本地文件失败（文件可能被移动/删除）");
                    return;
                }
                if (n != want) buf = Arrays.copyOf(buf, n);
                ClientNetworkHandlers.sendMediaUploadChunk(uploadId, buf);
                sentBytes += n;
                budget -= n;
            }
            notifyProgress();
            if (sentBytes >= totalBytes) {
                ClientNetworkHandlers.sendMediaUploadFinish(uploadId);
                state = STATE_WAIT_FINISH;
                deadlineMs = now + FINISH_TIMEOUT_MS;
                notifyListener();
            }
        } catch (Throwable t) {
            abortLocal("发送分块失败: " + t);
        }
    }

    // ───────────── 服务端回执 ─────────────

    /** 收到服务端上传状态回执（主线程调用）。 */
    public static void acceptStatus(int id, int status, String name, String message) {
        if (id != uploadId || state == STATE_IDLE) return; // 过期回执
        switch (status) {
            case com.cinemaforyou.network.MediaUploadStatusPayload.STATUS_READY -> {
                if (state != STATE_WAIT_READY) return;
                finalName = name == null ? "" : name;
                try {
                    in = new FileInputStream(file);
                } catch (Throwable t) {
                    abortLocal("无法读取本地文件: " + t);
                    return;
                }
                state = STATE_SENDING;
                chat("§7[CinemaForYou] 服务器已确认，开始上传: " + finalName);
                notifyListener();
            }
            case com.cinemaforyou.network.MediaUploadStatusPayload.STATUS_SUCCESS -> {
                String saved = (name == null || name.isEmpty()) ? finalName : name;
                reset();
                chat("§a[CinemaForYou] 上传完成: " + saved + "（已加入服务器媒体库）");
                // 上传完成后刷新媒体库列表（已打开的服务器媒体库界面会随之重建）
                MediaLibraryClient.request();
                notifyListener();
            }
            case com.cinemaforyou.network.MediaUploadStatusPayload.STATUS_CANCELLED -> {
                reset();
                chat("§e[CinemaForYou] 上传已取消");
                notifyListener();
            }
            default -> {
                String reason = (message == null || message.isEmpty()) ? "未知错误" : message;
                reset();
                chat("§c[CinemaForYou] 上传失败: " + reason);
                notifyListener();
            }
        }
    }

    // ───────────── 内部工具 ─────────────

    private static void abortLocal(String reason) {
        reset();
        chat("§c[CinemaForYou] " + reason);
        notifyListener();
    }

    private static void closeQuietly() {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignored) {}
            in = null;
        }
    }

    /** 进度通知：按 1% 或 250ms 节流，避免频繁重建界面。 */
    private static void notifyProgress() {
        long now = System.currentTimeMillis();
        int pct = percent();
        if (pct != lastNotifyPct && (now - lastNotifyMs >= 250L || pct >= 100)) {
            lastNotifyMs = now;
            lastNotifyPct = pct;
            notifyListener();
        }
        // 聊天栏里程碑提示（25%/50%/75%）
        int milestone = (pct / 25) * 25;
        if (milestone > lastChatPct && milestone > 0 && milestone < 100) {
            lastChatPct = milestone;
            chat("§7[CinemaForYou] 上传中 " + milestone + "%: " + fileName);
        }
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
        }
        if (bytes >= 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024);
        }
        return (bytes / 1024) + " KB";
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal(msg));
        }
    }
}
