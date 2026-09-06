package com.cinemaforyou.client.network;

import com.cinemaforyou.network.MediaListPayload;
import com.cinemaforyou.network.RequestMediaListPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.function.Consumer;

/**
 * 客户端"服务器媒体库"桥接：请求服务端文件列表并派发给当前打开的列表界面。
 */
@Environment(EnvType.CLIENT)
public final class MediaLibraryClient {

    private static volatile List<String> cache = null;
    private static volatile Consumer<List<String>> listener = null;

    private MediaLibraryClient() {}

    /** 当前界面注册监听（界面关闭时用 clearListener 解绑）。 */
    public static void setListener(Consumer<List<String>> onList) {
        listener = onList;
    }

    public static void clearListener() {
        listener = null;
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

    /** 收到服务端列表（主线程调用）。 */
    public static void accept(List<String> files) {
        cache = files;
        Consumer<List<String>> l = listener;
        if (l != null) {
            Minecraft.getInstance().execute(() -> l.accept(files));
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
