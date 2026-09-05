package com.cinemaforyou.client.render;

import com.cinemaforyou.data.CinemaScreen;
import com.cinemaforyou.data.ScreenOrientation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 屏幕四边形几何 + 渲染（26.2 GPU 抽象 API）。
 *
 * <p>根据 {@link CinemaScreen#corner1()}、{@link CinemaScreen#corner2()} 和
 * {@link ScreenOrientation} 计算四个顶点，通过 {@link SubmitNodeCollector#submitCustomGeometry}
 * 提交一个带 UV 的 quad。
 *
 * <p>渲染策略：
 * <ul>
 *   <li>始终先绘制黑色背板（{@link RenderTypes#debugQuads()}），避免视频未就绪或
 *       透明区域穿透场景；</li>
 *   <li>若 {@code textureId} 非空，再叠加视频纹理 quad；</li>
 *   <li>{@code textureId == null} 时仅显示黑屏（"加载中 / 错误 / 空闲"）。</li>
 * </ul>
 *
 * <p>视频 RenderType 自建（不复用 {@link RenderTypes#entityTranslucent}），走
 * <strong>不透明实体管线</strong>并在 {@code Sampler0} 上显式绑定三线性 CLAMP
 * 采样器：既避免远景缩小采样的噪点，也避免半透明管线"有效透明度<1"导致的
 * 背景漏出（黑色网格/细线噪点，随视角变化）。
 */
public final class ScreenQuad {

    /** 黑色 RGBA（不透明）。 */
    private static final int BLACK_ARGB = 0xFF000000;
    /** 每纹理缓存一个视频 RenderType（26.2 RenderType 需以名字+setup 创建）。 */
    private static final Map<Identifier, RenderType> VIDEO_RENDER_TYPES = new HashMap<>();

    private ScreenQuad() {}

    /** 视频 RenderType：ENTITY_TRANSLUCENT 管线 + Sampler0 显式三线性 CLAMP。 */
    private static RenderType videoRenderType(Identifier textureId) {
        return VIDEO_RENDER_TYPES.computeIfAbsent(textureId, id -> {
            RenderSetup setup = RenderSetup.builder(RenderPipelines.ENTITY_TRANSLUCENT)
                    .withTexture("Sampler0", id,
                            () -> RenderSystem.getSamplerCache()
                                    .getClampToEdge(FilterMode.LINEAR, true))
                    .useLightmap()
                    .useOverlay()
                    .affectsCrumbling()
                    .sortOnUpload()
                    .setOutline(RenderSetup.OutlineProperty.NONE)
                    .createRenderSetup();
            return RenderType.create("cinemaforyou_video", setup);
        });
    }

    /** 调试模式 3：走不透明实体管线（无 alpha 混合/透明排序，双面同色无歧义）。 */
    private static RenderType videoOpaqueRenderType(Identifier textureId) {
        Identifier cacheKey = Identifier.fromNamespaceAndPath(
                "cinemaforyou", "opaque/" + textureId.getPath());
        return VIDEO_RENDER_TYPES.computeIfAbsent(cacheKey, k -> {
            RenderSetup setup = RenderSetup.builder(RenderPipelines.ENTITY_SOLID)
                    .withTexture("Sampler0", textureId,
                            () -> RenderSystem.getSamplerCache()
                                    .getClampToEdge(FilterMode.LINEAR, true))
                    .useLightmap()
                    .useOverlay()
                    .affectsCrumbling()
                    .setOutline(RenderSetup.OutlineProperty.NONE)
                    .createRenderSetup();
            return RenderType.create("cinemaforyou_video_opaque", setup);
        });
    }

    /**
     * 渲染屏幕：先黑底，后纹理。
     *
     * @param submitCollector 当前帧提交收集器
     * @param poseStack       当前 pose 栈
     * @param screen          屏幕定义
     * @param textureId       视频纹理标识；为 null 表示无视频帧
     * @param cameraPos       相机世界坐标（用于视频面向相机外扩，避免与黑底 Z-fighting）
     */
    public static void render(
            SubmitNodeCollector submitCollector,
            PoseStack poseStack,
            CinemaScreen screen,
            Identifier textureId,
            double[] cameraPos) {
        float[] verts = computeVerts(screen);
        if (verts == null) return;
        float scale = Math.max(0.25f, screen.displayScalePercent() / 100.0f);
        if (Math.abs(scale - 1.0f) > 0.001f) {
            verts = scaleVerts(verts, scale);
        }
        int brightness = Math.max(0, Math.min(255,
                Math.round(screen.brightnessPercent() / 100.0f * 255.0f)));

        // 沿"面向相机"方向把屏幕整体外推，避免与背后的墙面/方块发生 Z-fighting
        // （远处看会闪黑色细线/小像素）。外推量与观看距离成正比：
        // 近处小到看不出"浮空"，远处足够大以对抗深度缓冲精度下降。
        double cx = (verts[0] + verts[3] + verts[6] + verts[9]) / 4.0;
        double cy = (verts[1] + verts[4] + verts[7] + verts[10]) / 4.0;
        double cz = (verts[2] + verts[5] + verts[8] + verts[11]) / 4.0;
        double dx = cameraPos[0] - cx, dy = cameraPos[1] - cy, dz = cameraPos[2] - cz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float push;
        if (dist < 0.001) {
            push = 0.06f;
        } else {
            dx /= dist;
            dy /= dist;
            dz /= dist;
            push = (float) Math.min(0.85, 0.05 + dist * 0.009);
        }
        final float offX = (float) (dx * push);
        final float offY = (float) (dy * push);
        final float offZ = (float) (dz * push);
        final float[] pushedVerts = new float[12];
        for (int i = 0; i < 12; i += 3) {
            pushedVerts[i] = verts[i] + offX;
            pushedVerts[i + 1] = verts[i + 1] + offY;
            pushedVerts[i + 2] = verts[i + 2] + offZ;
        }

        // 1. 黑色背板：仅在无视频帧时绘制（同样外推，避免加载/空闲阶段闪墙）。
        if (textureId == null) {
            renderBlackPanel(submitCollector, poseStack, pushedVerts);
            return;
        }

        int mode = com.cinemaforyou.CinemaForYouClient.clientConfig != null
                ? com.cinemaforyou.CinemaForYouClient.clientConfig.debugRenderMode : 0;

        // 调试模式 1/2：纯白不透明色块（无纹理），隔离"纹理/采样"因素
        if (mode == 1 || mode == 2) {
            renderSolidColor(submitCollector, poseStack, pushedVerts, 255, 255, 255, mode == 1, cameraPos);
            return;
        }

        // 视频像素已强制不透明。默认（mode 0/3）走【不透明实体管线】：
        // entityTranslucent 的半透明混合会让每像素有效透明度 < 1，视频面片会
        // 漏出背后世界——表现为白屏上"不规律网格+细线"噪点（随视角变化）。
        // 不透明管线经实测无噪点且亮度正常（debugRenderMode=3 验证）。
        boolean opaque = mode != 4; // mode 4 保留半透明单面供对比调试
        RenderType renderType = opaque ? videoOpaqueRenderType(textureId) : videoRenderType(textureId);
        boolean singleFace = mode == 4;

        final float[] fVerts = pushedVerts;
        final float fBrightness = brightness;
        submitCollector.order(0).submitCustomGeometry(
                poseStack, renderType,
                (pose, vertexConsumer) -> {
                    // 26.2 ENTITY 格式，元素必须按序全提交：
                    // position → color → uv0 → uv1(overlay占位) → uv2(光照) → normal
                    // 双面绘制：绕向不定的屏幕需正反两个绕向，否则背面被剔除成黑屏
                    int[][] order = {
                            {0, 3, 6, 9},   // 面1：左下→右下→右上→左上
                            {3, 0, 9, 6}    // 面2：右下→左下→左上→右上
                    };
                    float[][] uvs = {{0, 1, 1, 1, 1, 0, 0, 0}, {1, 1, 0, 1, 0, 0, 1, 0}};
                    int faces = singleFace ? cameraFacingFace(fVerts, cameraPos) : -1;
                    int faceCount = singleFace ? 1 : 2;
                    int faceStart = singleFace ? faces : 0;
                    for (int f = 0; f < faceCount; f++) {
                        int face = faceStart + f;
                        if (face < 0 || face > 1) continue;
                        for (int k = 0; k < 4; k++) {
                            int v = order[face][k];
                            vertexConsumer
                                    .addVertex(pose, fVerts[v], fVerts[v + 1], fVerts[v + 2])
                                    .setColor((int) fBrightness, (int) fBrightness, (int) fBrightness, 255)
                                    .setUv(uvs[face][k * 2], uvs[face][k * 2 + 1])
                                    .setUv1(0, 10) // 无受伤叠加
                                    .setUv2(240, 240) // 全亮度光照
                                    .setNormal(0, 1, 0);
                        }
                    }
                });
    }

    /**
     * 选择朝向相机的那一个绕向（mode=4 单面测试用）。
     * 返回 0 或 1；无法判断时返回 0。
     */
    private static int cameraFacingFace(float[] verts, double[] cameraPos) {
        // 面1 {0,3,6,9} 的法线 ≈ (v3-v0)×(v6-v0)，面2 相反
        float ax = verts[3] - verts[0], ay = verts[4] - verts[1], az = verts[5] - verts[2];
        float bx = verts[6] - verts[0], by = verts[7] - verts[1], bz = verts[8] - verts[2];
        double nx = ay * bz - az * by;
        double ny = az * bx - ax * bz;
        double nz = ax * by - ay * bx;
        double cx = (verts[0] + verts[3] + verts[6] + verts[9]) / 4.0 - cameraPos[0];
        double cy = (verts[1] + verts[4] + verts[7] + verts[10]) / 4.0 - cameraPos[1];
        double cz = (verts[2] + verts[5] + verts[8] + verts[11]) / 4.0 - cameraPos[2];
        return (nx * cx + ny * cy + nz * cz) >= 0 ? 0 : 1;
    }

    /** 调试模式 1/2：纯色不透明面板（debugQuads 管线，仅顶点色，无纹理）。 */
    private static void renderSolidColor(
            SubmitNodeCollector submitCollector,
            PoseStack poseStack,
            float[] verts,
            int r, int g, int b,
            boolean doubleSided,
            double[] cameraPos) {
        RenderType type = RenderTypes.debugQuads();
        int[] order0 = {0, 3, 6, 9};
        int[] order1 = {3, 0, 9, 6};
        submitCollector.order(0).submitCustomGeometry(
                poseStack, type,
                (pose, vc) -> {
                    int faces = doubleSided ? 2 : 1;
                    int startFace = doubleSided ? 0 : cameraFacingFace(verts, cameraPos);
                    for (int f = startFace; f < startFace + faces; f++) {
                        int[] ord = (f % 2 == 0) ? order0 : order1;
                        for (int k = 0; k < 4; k++) {
                            int v = ord[k];
                            vc.addVertex(pose, verts[v], verts[v + 1], verts[v + 2])
                                    .setColor(r, g, b, 255);
                        }
                    }
                });
    }

    /**
     * 绘制不透明黑色背板（无纹理）。使用 {@link RenderTypes#debugQuads()}，
     * 该 RenderType 不绑定纹理，仅依赖顶点颜色。
     */
    private static void renderBlackPanel(
            SubmitNodeCollector submitCollector,
            PoseStack poseStack,
            float[] verts) {
        RenderType blackType = RenderTypes.debugQuads();
        submitCollector.order(0).submitCustomGeometry(
                poseStack, blackType,
                (pose, vertexConsumer) -> {
                    // 全黑 RGBA(0,0,0,255)：setColor 接收 RGBA 字节
                    vertexConsumer
                            .addVertex(pose, verts[0], verts[1], verts[2])
                            .setColor(0, 0, 0, 255);
                    vertexConsumer
                            .addVertex(pose, verts[3], verts[4], verts[5])
                            .setColor(0, 0, 0, 255);
                    vertexConsumer
                            .addVertex(pose, verts[6], verts[7], verts[8])
                            .setColor(0, 0, 0, 255);
                    vertexConsumer
                            .addVertex(pose, verts[9], verts[10], verts[11])
                            .setColor(0, 0, 0, 255);
                });
    }

    /**
     * 绘制轴对齐盒体的半透明填充（6 个面，debugQuads）。
     *
     * <p>用于对角点选择预览：绿色 = 实时选择中，黄色 = 确认中。
     *
     * @param box       盒体（世界坐标）
     * @param r         红 0-255
     * @param g         绿 0-255
     * @param b         蓝 0-255
     * @param alpha     透明度 0-255
     */
    public static void renderBox(
            SubmitNodeCollector submitCollector,
            PoseStack poseStack,
            net.minecraft.world.phys.AABB box,
            int r, int g, int b, int alpha) {
        RenderType type = RenderTypes.debugQuads();

        double minX = box.minX, minY = box.minY, minZ = box.minZ;
        double maxX = box.maxX, maxY = box.maxY, maxZ = box.maxZ;

        submitCollector.order(0).submitCustomGeometry(
                poseStack, type,
                (pose, vc) -> {
                    // +Z 面
                    quad(vc, pose, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
                    // -Z 面
                    quad(vc, pose, maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, alpha);
                    // +X 面
                    quad(vc, pose, maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, alpha);
                    // -X 面
                    quad(vc, pose, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, alpha);
                    // +Y 面
                    quad(vc, pose, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ, r, g, b, alpha);
                    // -Y 面
                    quad(vc, pose, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, alpha);
                });
    }

    /** 提交单个四边形（顶点顺时针，正面朝外）。 */
    private static void quad(
            VertexConsumer vc, com.mojang.blaze3d.vertex.PoseStack.Pose pose,
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double x3, double y3, double z3,
            double x4, double y4, double z4,
            int r, int g, int b, int a) {
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
        vc.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(r, g, b, a);
        vc.addVertex(pose, (float) x4, (float) y4, (float) z4).setColor(r, g, b, a);
    }

    /**
     * 计算屏幕 quad 的四个顶点（世界坐标）。
     *
     * @return 12 个 float（4 个顶点 × xyz），或 null 表示无法计算
     */
    private static float[] computeVerts(CinemaScreen screen) {
        double x1 = screen.corner1().getX();
        double y1 = screen.corner1().getY();
        double z1 = screen.corner1().getZ();
        double x2 = screen.corner2().getX();
        double y2 = screen.corner2().getY();
        double z2 = screen.corner2().getZ();

        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2);
        double maxY = Math.max(y1, y2);
        double minZ = Math.min(z1, z2);
        double maxZ = Math.max(z1, z2);

        ScreenOrientation orient = screen.orientation();
        switch (orient) {
            case AXIS_Z -> {
                double z = maxZ + 1.01;
                return new float[] {
                        (float) minX, (float) minY, (float) z,
                        (float) (maxX + 1), (float) minY, (float) z,
                        (float) (maxX + 1), (float) (maxY + 1), (float) z,
                        (float) minX, (float) (maxY + 1), (float) z
                };
            }
            case AXIS_X -> {
                double x = maxX + 1.01;
                return new float[] {
                        (float) x, (float) minY, (float) maxZ,
                        (float) x, (float) minY, (float) minZ,
                        (float) x, (float) (maxY + 1), (float) minZ,
                        (float) x, (float) (maxY + 1), (float) maxZ
                };
            }
            case AXIS_Y -> {
                double y = maxY + 1.01;
                return new float[] {
                        (float) minX, (float) y, (float) minZ,
                        (float) (maxX + 1), (float) y, (float) minZ,
                        (float) (maxX + 1), (float) y, (float) (maxZ + 1),
                        (float) minX, (float) y, (float) (maxZ + 1)
                };
            }
            default -> { return null; }
        }
    }

    private static float[] scaleVerts(float[] verts, float scale) {
        float cx = (verts[0] + verts[3] + verts[6] + verts[9]) / 4.0f;
        float cy = (verts[1] + verts[4] + verts[7] + verts[10]) / 4.0f;
        float cz = (verts[2] + verts[5] + verts[8] + verts[11]) / 4.0f;
        float[] scaled = new float[verts.length];
        for (int i = 0; i < verts.length; i += 3) {
            scaled[i] = cx + (verts[i] - cx) * scale;
            scaled[i + 1] = cy + (verts[i + 1] - cy) * scale;
            scaled[i + 2] = cz + (verts[i + 2] - cz) * scale;
        }
        return scaled;
    }
}
