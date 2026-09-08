package com.cinemaforyou.client.network;

import java.util.List;
import java.util.UUID;

/**
 * 屏幕元数据客户端缓存（服务端 S2C 到达后更新并通知监听界面刷新）。
 */
public final class ScreenAdminClient {

    private static volatile List<com.cinemaforyou.network.ScreenMetaPayload.Entry> metas =
            List.of();
    private static Runnable listener = null;

    private ScreenAdminClient() {}

    /** 服务端元数据到达（客户端线程）。 */
    public static void accept(List<com.cinemaforyou.network.ScreenMetaPayload.Entry> entries) {
        metas = entries == null ? List.of() : entries;
        Runnable l = listener;
        if (l != null) {
            try {
                l.run();
            } catch (Throwable ignored) {}
        }
    }

    public static List<com.cinemaforyou.network.ScreenMetaPayload.Entry> metas() {
        return metas;
    }

    /** 查找某屏描述（不存在返回空串）。 */
    public static String descOf(UUID id) {
        for (com.cinemaforyou.network.ScreenMetaPayload.Entry e : metas) {
            if (e.id().equals(id)) return e.desc() == null ? "" : e.desc();
        }
        return "";
    }

    /** 查找某屏创建者显示名（未知返回 null）。 */
    public static String ownerOf(UUID id) {
        for (com.cinemaforyou.network.ScreenMetaPayload.Entry e : metas) {
            if (e.id().equals(id)) return e.ownerName();
        }
        return null;
    }

    /** 界面设置刷新回调（同一时刻只有一个管理界面）。 */
    public static void setListener(Runnable l) {
        listener = l;
    }
}
