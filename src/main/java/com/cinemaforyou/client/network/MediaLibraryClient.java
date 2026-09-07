package com.cinemaforyou.client.network;

import com.cinemaforyou.network.MediaListPayload;
import com.cinemaforyou.network.RequestMediaListPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 客户端"服务器媒体库"桥接：请求服务端文件列表并把结果派发给当前打开的列表界面。
 * 附带文件元数据（添加者/修改时间）。
 */
@Environment(EnvType.CLIENT)
public final class MediaLibraryClient {

    private static volatile List<String> cache = null;
    private static volatile Consumer<List<String>> listener = null;
    private static volatile Map<String, com.cinemaforyou.network.MediaMetaPayload.Entry> meta =
            new HashMap<>();
    private static volatile Runnable metaListener = null;

    private MediaLibraryClient() {}

    /** 当前界面注册监听（界面关闭时用 clearListener 解绑）。 */
    public static void setListener(Consumer<List<String>> onList) {
        listener = onList;
    }

    public static void clearListener() {
        listener = null;
    }

    /** 元数据就绪后刷新界面。 */
    public static void setMetaListener(Runnable onMeta) {
        metaListener = onMeta;
    }

    /** 请求一次最新列表（随后把结果交给监听者并缓存）。 */
    public static void request() {
        cache = null;
        com.cinemaforyou.client.network.ClientNetworkHandlers.sendMediaListRequest();
    }

    /** 最近一次拿到的列表（null = 尚未加载/请求中）。 */
    public static List<String> cached() {
        return cache;
    }

    /** 某文件的元数据（暂无返回 null）。 */
    public static com.cinemaforyou.network.MediaMetaPayload.Entry metaOf(String name) {
        return meta.get(name);
    }

    /** 收到服务端列表（主线程调用）。 */
    public static void accept(List<String> files) {
        cache = files;
        Consumer<List<String>> l = listener;
        if (l != null) {
            Minecraft.getInstance().execute(() -> l.accept(files));
        }
    }

    /** 收到服务端元数据。 */
    public static void acceptMeta(List<com.cinemaforyou.network.MediaMetaPayload.Entry> entries) {
        Map<String, com.cinemaforyou.network.MediaMetaPayload.Entry> m = new HashMap<>();
        for (com.cinemaforyou.network.MediaMetaPayload.Entry e : entries) {
            m.put(e.name(), e);
        }
        meta = m;
        Runnable r = metaListener;
        if (r != null) {
            Minecraft.getInstance().execute(r);
        }
    }

    // 让接收器注册与发包方法都走 ClientNetworkHandlers（见其内部实现）
    static void registerReceiver() {
    }

    /** 播放服务器媒体：用 file:文件名 让服务端自动改写成 http 直链。 */
    public static String sourceFor(String fileName) {
        return "file:" + fileName;
    }
}
