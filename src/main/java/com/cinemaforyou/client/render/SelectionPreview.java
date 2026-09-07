package com.cinemaforyou.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * 对角点选择预览状态 + 盒体几何计算（纯客户端）。
 *
 * <p>生命周期：
 * <ul>
 *   <li>选中角点1：{@link #beginSelecting(BlockPos)} → 世界中实时渲染
 *       角点1 → 准星目标方块的半透明盒体</li>
 *   <li>选中角点2 / 打开 URL 输入屏：{@link #beginConfirming(BlockPos, BlockPos)}
 *       → 渲染最终区域盒体，直到 {@link #clear()}</li>
 *   <li>超时（60 秒）或关闭界面后自动清除</li>
 * </ul>
 *
 * <p>渲染开关：{@code ClientConfig.showSelectionBox}。
 */
@Environment(EnvType.CLIENT)
public final class SelectionPreview {

    /** 选择阶段超时（毫秒）。 */
    private static final long TIMEOUT_MS = 60_000;

    /** 当前状态：null=无，corner1=选择中，corner1+corner2=确认中。 */
    private static volatile BlockPos corner1;
    private static volatile BlockPos corner2;
    private static volatile long startedAt;

    private SelectionPreview() {}

    /** 角点1已选定，进入实时预览阶段。 */
    public static void beginSelecting(BlockPos pos) {
        corner1 = pos.immutable();
        corner2 = null;
        startedAt = System.currentTimeMillis();
    }

    /** 角点2已选定（URL 输入屏打开期间持续显示最终范围）。 */
    public static void beginConfirming(BlockPos c1, BlockPos c2) {
        corner1 = c1.immutable();
        corner2 = c2.immutable();
        startedAt = System.currentTimeMillis();
    }

    /** URL 输入屏关闭时调用（无论是否创建成功）。 */
    public static void clear() {
        corner1 = null;
        corner2 = null;
    }

    /** 盒体向外扩放量：避免面与方块表面 Z-fighting 导致不可见。 */
    private static final double EXPAND = 0.01;

    /** 获取当前预览盒体（含超时判定）；无预览返回 null。 */
    public static AABB currentBox() {
        BlockPos c1 = corner1;
        if (c1 == null) return null;
        if (System.currentTimeMillis() - startedAt > TIMEOUT_MS) {
            clear();
            return null;
        }
        BlockPos c2 = corner2;
        if (c2 != null) {
            return new AABB(
                    Math.min(c1.getX(), c2.getX()) - EXPAND,
                    Math.min(c1.getY(), c2.getY()) - EXPAND,
                    Math.min(c1.getZ(), c2.getZ()) - EXPAND,
                    Math.max(c1.getX(), c2.getX()) + 1 + EXPAND,
                    Math.max(c1.getY(), c2.getY()) + 1 + EXPAND,
                    Math.max(c1.getZ(), c2.getZ()) + 1 + EXPAND);
        }
        return null; // 选择中阶段的盒体由渲染器根据准星动态计算
    }

    /** 由两个角点构建外扩后的盒体（选择中实时预览用）。 */
    public static AABB boxOf(BlockPos c1, BlockPos c2) {
        return new AABB(
                Math.min(c1.getX(), c2.getX()) - EXPAND,
                Math.min(c1.getY(), c2.getY()) - EXPAND,
                Math.min(c1.getZ(), c2.getZ()) - EXPAND,
                Math.max(c1.getX(), c2.getX()) + 1 + EXPAND,
                Math.max(c1.getY(), c2.getY()) + 1 + EXPAND,
                Math.max(c1.getZ(), c2.getZ()) + 1 + EXPAND);
    }

    /** 是否处于"已选角点1、等待角点2"的实时预览阶段。 */
    public static boolean isSelecting() {
        return corner1 != null && corner2 == null;
    }

    /** 选择中阶段的第一个角点。 */
    public static BlockPos selectingCorner1() {
        return corner1;
    }

    /** 确认中阶段的两个角点（corner2 非空时）。 */
    public static BlockPos confirmingCorner2() {
        return corner2;
    }
}
