package com.cinemaforyou.data;

import net.minecraft.core.Direction;

/**
 * 屏幕朝向：决定四边形法向与贴图方向。
 *
 * <p>由玩家选择两个对角点时，根据两点共面的轴向自动推断。
 */
public enum ScreenOrientation {
    /** 屏幕面朝 +X / -X 方向（即屏幕平面在 YZ 上展开）。 */
    AXIS_X,
    /** 屏幕面朝 +Y / -Y 方向（即屏幕平面在 XZ 上展开，天花板/地板屏）。 */
    AXIS_Y,
    /** 屏幕面朝 +Z / -Z 方向（即屏幕平面在 XY 上展开，最常见的墙面屏）。 */
    AXIS_Z;

    /** 根据两个对角点推断屏幕朝向：两点的 Y 相同→AXIS_Z（墙面），
     *  X 相同→AXIS_Z，Z 相同→AXIS_X，其它情况按差值最大的轴推断。 */
    public static ScreenOrientation fromCorners(
            net.minecraft.core.BlockPos c1, net.minecraft.core.BlockPos c2) {
        int dx = Math.abs(c2.getX() - c1.getX());
        int dy = Math.abs(c2.getY() - c1.getY());
        int dz = Math.abs(c2.getZ() - c1.getZ());
        // 两点共面于哪一轴的法平面？
        if (dx == 0) return AXIS_X; // X 固定 → 屏幕在 YZ 平面
        if (dy == 0) return AXIS_Y; // Y 固定 → 屏幕在 XZ 平面
        if (dz == 0) return AXIS_Z; // Z 固定 → 屏幕在 XY 平面
        // 三轴都不共面 → 非法，回退到最常见的 Z 朝向
        return AXIS_Z;
    }

    /** 转为 vanilla Direction（取正方向，仅用于提示性显示）。 */
    public Direction toDirection() {
        return switch (this) {
            case AXIS_X -> Direction.EAST;
            case AXIS_Y -> Direction.UP;
            case AXIS_Z -> Direction.SOUTH;
        };
    }
}
