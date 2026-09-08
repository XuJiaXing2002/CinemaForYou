package com.cinemaforyou.client.video;

import com.cinemaforyou.CinemaForYouClient;
import com.cinemaforyou.client.config.ClientConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频标题后台抓取器：为历史记录 / 播放队列里的网页链接惰性获取标题，
 * 成功后写入 {@link ClientConfig#urlTitles} 并持久化（只抓一次）。
 *
 * <p>界面展示名字的优先级统一由 {@link ClientConfig#displayNameFor(String)}
 * 决定：备注 &gt; 标题 &gt; 链接截断。本类只负责把"标题"填进缓存。
 */
@Environment(EnvType.CLIENT)
public final class VideoTitleResolver {

    private static final Set<String> PENDING = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> FAILED = Collections.synchronizedSet(new HashSet<>());

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CinemaForYou-TitleFetcher");
        t.setDaemon(true);
        return t;
    });

    /** 当前正在展示历史/队列的界面注册的刷新回调（抓取完成后主线程调用）。 */
    private static volatile Runnable onUpdated = null;

    private VideoTitleResolver() {}

    /** 界面（历史/队列）在 init 时注册自身重建回调；同屏唯一，后注册者覆盖。 */
    public static void setListener(Runnable r) {
        onUpdated = r;
    }

    /**
     * 请求抓取某链接的标题（幂等）：已缓存/抓取中/已失败/无需抓取 直接忽略。
     */
    public static void request(String url) {
        if (url == null) return;
        String key = url.trim();
        if (key.isEmpty() || !UrlResolver.needsWebTitle(key)) return;
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (cfg == null) return;
        if (cfg.titleFor(key) != null) return;
        if (FAILED.contains(key)) return;       // 本次会话已失败过，不反复请求
        if (!PENDING.add(key)) return;          // 已在抓取
        WORKER.execute(() -> fetch(key));
    }

    private static void fetch(String key) {
        String title = null;
        try {
            title = UrlResolver.fetchTitle(key);
        } catch (Throwable ignored) {
            title = null;
        } finally {
            PENDING.remove(key);
        }
        ClientConfig cfg = CinemaForYouClient.clientConfig;
        if (title == null || title.isBlank()) {
            FAILED.add(key);
            fire(); // 也刷新一次：让界面停止"等待"状态（名称不变化）
            return;
        }
        if (cfg != null) {
            cfg.setTitle(key, title);
        }
        fire();
    }

    private static void fire() {
        Runnable r = onUpdated;
        if (r == null) return;
        try {
            Minecraft.getInstance().execute(r);
        } catch (Throwable ignored) {}
    }
}
