package com.cinemaforyou.client.network;

import net.minecraft.client.Minecraft;

import java.util.List;

/** 服务器播放历史缓存（谁/何时/播放内容）。 */
public final class PlayLogClient {

    private static volatile List<com.cinemaforyou.network.PlayLogPayload.Entry> entries = List.of();
    private static Runnable listener = null;

    private PlayLogClient() {}

    public static void accept(List<com.cinemaforyou.network.PlayLogPayload.Entry> list) {
        entries = list == null ? List.of() : list;
        Runnable l = listener;
        if (l != null) {
            Minecraft.getInstance().execute(l);
        }
    }

    public static List<com.cinemaforyou.network.PlayLogPayload.Entry> entries() {
        return entries;
    }

    public static void setListener(Runnable r) {
        listener = r;
    }
}
