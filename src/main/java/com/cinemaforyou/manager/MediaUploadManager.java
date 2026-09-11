package com.cinemaforyou.manager;

import com.cinemaforyou.CinemaForYou;
import com.cinemaforyou.network.MediaUploadStatusPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端"本地上传到服务器媒体库"会话管理（分块接收 → 重组写盘）。
 *
 * <p>流程：客户端 {@code MediaUploadStartPayload}（文件名+总大小）→ 服务端校验
 * （仅 OP≥2、视频扩展名白名单、重名自动加序号）并回 READY（含最终文件名）→
 * 客户端按块发 {@code MediaUploadChunkPayload}（约 256KB/块）→ 全部发完发
 * {@code MediaUploadFinishPayload} → 服务端校验字节数一致后把临时文件移入
 * {@link MediaHttpServer#mediaDirectory()}（服务器目录/cinema/videos），并登记添加者。
 *
 * <p>所有方法都在服务端主线程被调用（接收器里 {@code server.execute}），
 * 因此这里用普通 HashMap 即可；临时块文件放在媒体目录下的 {@code .upload_tmp}
 * 子目录，避免上传中的半成品出现在媒体库列表里。
 */
public final class MediaUploadManager {

    /** 允许上传的媒体扩展名（与本地视频库/文件选择屏同一套白名单）。 */
    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".webm", ".mov", ".avi",
        ".flv", ".wmv", ".ts", ".m4v", ".mp3", ".m4a", ".wav", ".flac", ".ogg", ".aac"};

    /** 单块数据上限（客户端发 256KB；留一倍余量，超限直接判为异常）。 */
    private static final int CHUNK_LIMIT = 512 * 1024;

    /** 声明文件大小上限（防止脚本伪造超大请求；按需可调整）。 */
    private static final long MAX_UPLOAD_BYTES = 16L * 1024 * 1024 * 1024; // 16 GiB

    /** 上传会话闲置超时（超过则丢弃临时文件）。 */
    private static final long SESSION_TIMEOUT_MS = 5 * 60_000L;

    /** 临时块文件目录名（媒体目录下，listMediaFiles 只列文件不列目录）。 */
    private static final String TEMP_DIR_NAME = ".upload_tmp";

    /** 玩家 UUID → 进行中的上传会话（同一玩家同时只允许一个）。 */
    private static final Map<UUID, Session> sessions = new HashMap<>();

    /** 已被上传会话预定的最终文件名（小写），防止并发上传撞名。 */
    private static final Set<String> reservedNames = new HashSet<>();

    private MediaUploadManager() {}

    /** 一次上传会话。 */
    private static final class Session {
        final int uploadId;
        final File temp;
        final OutputStream out;
        final long declaredSize;
        final String finalName;
        long received;
        long lastActiveMs;

        Session(int uploadId, File temp, OutputStream out, long declaredSize, String finalName) {
            this.uploadId = uploadId;
            this.temp = temp;
            this.out = out;
            this.declaredSize = declaredSize;
            this.finalName = finalName;
            this.lastActiveMs = System.currentTimeMillis();
        }
    }

    // ───────────── 客户端请求入口 ─────────────

    /** 开始上传：校验权限/扩展名/大小，确定最终文件名，创建临时文件。 */
    public static void handleStart(ServerPlayer player, int uploadId, String rawName, long size) {
        cleanupStaleSessions();
        if (!isOp(player)) {
            fail(player, uploadId, "只有管理员（OP≥2）可以上传视频到服务器媒体库");
            return;
        }
        String name = sanitizeFileName(rawName);
        if (name.isEmpty()) {
            fail(player, uploadId, "非法的文件名");
            return;
        }
        if (!isSupportedVideoName(name)) {
            fail(player, uploadId, "不支持的视频格式（仅限 "
                    + String.join("/", VIDEO_EXTS) + "）: " + name);
            return;
        }
        if (size <= 0 || size > MAX_UPLOAD_BYTES) {
            fail(player, uploadId, "文件大小非法或超过上限（"
                    + (MAX_UPLOAD_BYTES / (1024 * 1024 * 1024)) + " GiB）");
            return;
        }
        try {
            File dir = MediaHttpServer.mediaDirectory();
            Files.createDirectories(dir.toPath());
            // 同一玩家的旧会话（重试/超时残留）直接丢弃，避免临时文件泄漏
            Session old = sessions.remove(player.getUUID());
            if (old != null) {
                abortSession(old);
            }
            File tempDir = new File(dir, TEMP_DIR_NAME);
            Files.createDirectories(tempDir.toPath());
            File temp = new File(tempDir, "upload-" + UUID.randomUUID() + ".part");
            OutputStream out = new BufferedOutputStream(new FileOutputStream(temp), 256 * 1024);
            String finalName = resolveAvailableName(dir, name);
            Session s = new Session(uploadId, temp, out, size, finalName);
            sessions.put(player.getUUID(), s);
            reservedNames.add(finalName.toLowerCase(Locale.ROOT));
            sendStatus(player, uploadId, MediaUploadStatusPayload.STATUS_READY, finalName,
                    "可以开始发送分块");
            CinemaForYou.LOGGER.info("[CinemaForYou] {} 开始上传媒体: {} ({} 字节) → {}",
                    player.getScoreboardName(), name, size, finalName);
        } catch (Throwable t) {
            fail(player, uploadId, "无法创建上传临时文件: " + t);
        }
    }

    /** 接收一块数据并追加写入临时文件。 */
    public static void handleChunk(ServerPlayer player, int uploadId, byte[] data) {
        Session s = validSession(player, uploadId);
        if (s == null) return; // 会话已被取消/替换：静默忽略过期分块
        if (data == null || data.length == 0) {
            abort(player, s, "收到空分块");
            return;
        }
        if (data.length > CHUNK_LIMIT) {
            abort(player, s, "单个分块过大（" + data.length + " 字节）");
            return;
        }
        if (s.received + data.length > s.declaredSize) {
            abort(player, s, "实际数据超过声明大小");
            return;
        }
        try {
            s.out.write(data);
            s.received += data.length;
            s.lastActiveMs = System.currentTimeMillis();
        } catch (Throwable t) {
            abort(player, s, "写入分块失败: " + t);
        }
    }

    /** 结束上传：校验大小一致后落盘到媒体目录并登记添加者。 */
    public static void handleFinish(ServerPlayer player, int uploadId) {
        Session s = validSession(player, uploadId);
        if (s == null) {
            // 会话已失效（超时/被取消）：告知客户端不要再等
            sendStatus(player, uploadId, MediaUploadStatusPayload.STATUS_ERROR, "",
                    "上传会话已失效（超时或被取消）");
            return;
        }
        sessions.remove(player.getUUID());
        try {
            s.out.close();
        } catch (Throwable ignored) {}
        if (s.received != s.declaredSize) {
            reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
            deleteQuietly(s.temp);
            fail(player, uploadId, "接收字节数（" + s.received + "）与声明大小（"
                    + s.declaredSize + "）不一致，已丢弃");
            return;
        }
        try {
            File dir = MediaHttpServer.mediaDirectory();
            // 会话进行期间可能有人放进同名文件：再次确认重名，必要时换名
            if (new File(dir, s.finalName).exists()) {
                reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
                String renamed = resolveAvailableName(dir, s.finalName);
                reservedNames.add(renamed.toLowerCase(Locale.ROOT));
                Session renamedSession = new Session(s.uploadId, s.temp, s.out, s.declaredSize,
                        renamed);
                renamedSession.received = s.received;
                s = renamedSession;
            }
            File target = new File(dir, s.finalName);
            try {
                Files.move(s.temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFailed) {
                Files.move(s.temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
            ScreenManager mgr = CinemaForYou.screenManager;
            if (mgr != null) {
                mgr.registerMediaOwner(s.finalName, player);
            }
            sendStatus(player, s.uploadId, MediaUploadStatusPayload.STATUS_SUCCESS, s.finalName,
                    "上传完成");
            player.sendSystemMessage(Component.literal(
                    "§a[CinemaForYou] 上传完成: " + s.finalName + "（已保存到服务器媒体库）"));
            CinemaForYou.LOGGER.info("[CinemaForYou] {} 上传媒体完成: {} ({} 字节)",
                    player.getScoreboardName(), s.finalName, s.declaredSize);
        } catch (Throwable t) {
            deleteQuietly(s.temp);
            reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
            fail(player, s.uploadId, "保存文件失败: " + t);
        }
    }

    /** 取消上传：删除临时文件（已落盘的文件不受影响）。 */
    public static void handleCancel(ServerPlayer player, int uploadId) {
        Session s = sessions.get(player.getUUID());
        if (s == null || s.uploadId != uploadId) return;
        sessions.remove(player.getUUID());
        try {
            s.out.close();
        } catch (Throwable ignored) {}
        reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
        deleteQuietly(s.temp);
        sendStatus(player, uploadId, MediaUploadStatusPayload.STATUS_CANCELLED, "", "已取消上传");
        player.sendSystemMessage(Component.literal(
                "§e[CinemaForYou] 已取消上传（临时数据已清理）"));
    }

    /** 玩家断线：丢弃其未完成的上传并清理临时文件。 */
    public static void onPlayerDisconnect(UUID playerId) {
        Session s = sessions.remove(playerId);
        if (s == null) return;
        abortSession(s);
        CinemaForYou.LOGGER.info("[CinemaForYou] 玩家断开，已清理未完成上传: {}", s.finalName);
    }

    // ───────────── 内部工具 ─────────────

    /** 校验会话归属与 uploadId；不匹配返回 null。 */
    private static Session validSession(ServerPlayer player, int uploadId) {
        Session s = sessions.get(player.getUUID());
        if (s == null || s.uploadId != uploadId) return null;
        s.lastActiveMs = System.currentTimeMillis();
        return s;
    }

    /** 是否 OP（权限等级 ≥2），与删除/队列等"仅管理员"入口同一套判定。 */
    private static boolean isOp(ServerPlayer player) {
        return player.createCommandSourceStack().permissions().hasPermission(
                new net.minecraft.server.permissions.Permission.HasCommandLevel(
                        net.minecraft.server.permissions.PermissionLevel.GAMEMASTERS));
    }

    /** 允许的媒体扩展名（服务器端独立校验，不信任客户端）。 */
    public static boolean isSupportedVideoName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : VIDEO_EXTS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** 清理文件名：去掉目录部分、替换 Windows 非法字符、限制长度（保留扩展名）。 */
    static String sanitizeFileName(String raw) {
        if (raw == null) return "";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == 127 || "<>:\"|?*".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        name = sb.toString().replaceAll("[. ]+$", "");
        while (!name.isEmpty() && name.charAt(0) == ' ') name = name.substring(1);
        if (name.isEmpty() || name.startsWith(".")) return "";
        if (name.length() > 150) {
            int dot = name.lastIndexOf('.');
            String ext = (dot > 0 && name.length() - dot <= 10) ? name.substring(dot) : "";
            name = name.substring(0, Math.max(1, 150 - ext.length())) + ext;
        }
        return name;
    }

    /** 重名时自动加序号（不改扩展名）：name.mp4 → name (1).mp4 → name (2).mp4 … */
    static String resolveAvailableName(File dir, String desired) {
        String base = desired;
        String ext = "";
        int dot = desired.lastIndexOf('.');
        if (dot > 0) {
            base = desired.substring(0, dot);
            ext = desired.substring(dot);
        }
        String candidate = desired;
        for (int i = 1; new File(dir, candidate).exists()
                || reservedNames.contains(candidate.toLowerCase(Locale.ROOT)); i++) {
            candidate = base + " (" + i + ")" + ext;
        }
        return candidate;
    }

    /** 丢弃闲置超时的会话（含断线残留），并清理超过 1 天的孤儿临时文件。 */
    private static void cleanupStaleSessions() {
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<UUID, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Session> e = it.next();
            Session s = e.getValue();
            if (now - s.lastActiveMs > SESSION_TIMEOUT_MS) {
                it.remove();
                reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
                try {
                    s.out.close();
                } catch (Throwable ignored) {}
                deleteQuietly(s.temp);
                sendStatusToUuid(e.getKey(), s.uploadId, MediaUploadStatusPayload.STATUS_ERROR, "",
                        "上传超时，已取消");
            }
        }
        try {
            File tempDir = new File(MediaHttpServer.mediaDirectory(), TEMP_DIR_NAME);
            File[] children = tempDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (f.isFile() && now - f.lastModified() > 24L * 60 * 60 * 1000) {
                        deleteQuietly(f);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 给指定 UUID 的在线玩家发状态（离线则忽略）。 */
    private static void sendStatusToUuid(UUID playerId, int uploadId, int status,
                                         String name, String message) {
        try {
            var server = CinemaForYou.screenManager != null
                    ? CinemaForYou.screenManager.getServer() : null;
            if (server == null) return;
            ServerPlayer p = server.getPlayerList().getPlayer(playerId);
            if (p != null) sendStatus(p, uploadId, status, name, message);
        } catch (Throwable ignored) {}
    }

    private static void sendStatus(ServerPlayer player, int uploadId, int status,
                                   String name, String message) {
        if (ServerPlayNetworking.canSend(player, MediaUploadStatusPayload.TYPE)) {
            ServerPlayNetworking.send(player, new MediaUploadStatusPayload(
                    uploadId, status, name == null ? "" : name, message == null ? "" : message));
        }
    }

    /** 通用失败处理：回执 + 聊天提示（对不上的会话不会走到这里）。 */
    private static void fail(ServerPlayer player, int uploadId, String reason) {
        sendStatus(player, uploadId, MediaUploadStatusPayload.STATUS_ERROR, "", reason);
        player.sendSystemMessage(Component.literal("§c[CinemaForYou] 上传失败: " + reason));
    }

    /** 会话异常中止：清理临时文件与占位并通知玩家。 */
    private static void abort(ServerPlayer player, Session s, String reason) {
        sessions.remove(player.getUUID(), s);
        reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
        try {
            s.out.close();
        } catch (Throwable ignored) {}
        deleteQuietly(s.temp);
        sendStatus(player, s.uploadId, MediaUploadStatusPayload.STATUS_ERROR, "", reason);
        player.sendSystemMessage(Component.literal("§c[CinemaForYou] 上传失败: " + reason));
    }

    /** 静默丢弃会话（不做玩家提示）。 */
    private static void abortSession(Session s) {
        reservedNames.remove(s.finalName.toLowerCase(Locale.ROOT));
        try {
            s.out.close();
        } catch (Throwable ignored) {}
        deleteQuietly(s.temp);
    }

    private static void deleteQuietly(File f) {
        if (f == null) return;
        try {
            Files.deleteIfExists(f.toPath());
        } catch (Throwable ignored) {}
    }
}
