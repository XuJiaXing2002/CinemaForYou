package com.cinemaforyou.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.HashMap;
import java.util.Map;

/**
 * 界面返回链：open() 记录"从哪个界面打开"，back() 让当前界面回到上级。
 * 供全部模组界面（含不继承 ScrollableSettingsScreen 的普通 Screen）共用。
 */
public final class GuiNav {

    private static final Map<Screen, Screen> PARENTS = new HashMap<>();

    private GuiNav() {}

    /** 打开 to，并记录 from 为它的上级（from 可为 null=根）。 */
    public static void open(Screen from, Screen to) {
        if (to == null) return;
        if (from != null) {
            PARENTS.put(to, from);
        } else {
            PARENTS.remove(to);
        }
        Minecraft.getInstance().gui.setScreen(to);
    }

    /** 当前界面返回上级；无上级返回 false（调用方走默认关闭）。 */
    public static boolean back(Screen self) {
        if (self == null) return false;
        Screen parent = PARENTS.remove(self);
        if (parent != null) {
            Minecraft.getInstance().gui.setScreen(parent);
            return true;
        }
        return false;
    }
}
