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
        float[] surface = verts;
        // 顺序关键：先在未倾斜的原始平面上生成弧面网格（弧面隆起方向=平面法线，
        // 若先倾斜再弯弧，弧面会随倾角塌扁，90° 时退化成平面）；
        // 再整体倾斜（绕表面中心旋转）；最后统一做相机外推。
        GridData curvedGrid = screen.isCurved() ? computeCurvedGrid(screen, verts) : null;
        if (curvedGrid != null) {
            surface = curvedGrid.verts;
        }
        surface = applyTilt(screen, surface);
        float[] displayVerts = offsetVerts(surface, offX, offY, offZ);
        boolean curved = curvedGrid != null;

        // 1. 黑色背板：仅在无视频帧时绘制（同样外推，避免加载/空闲阶段闪墙）。
        //    曲面屏无帧时也画同形状的曲面黑底，避免"平面黑底+曲面画面"。
        if (textureId == null) {
            if (curved) {
                submitGridColor(submitCollector, poseStack,
                        new GridData(displayVerts, curvedGrid.cols, curvedGrid.rows,
                        curvedGrid.uEdge, curvedGrid.vEdge),
                        0, 0, 0, 255);
                return;
            }
            renderBlackPanel(submitCollector, poseStack, displayVerts);
            return;
        }

        int mode = com.cinemaforyou.CinemaForYouClient.clientConfig != null
                ? com.cinemaforyou.CinemaForYouClient.clientConfig.debugRenderMode : 0;

        // 调试模式 1/2：纯白不透明色块（无纹理），隔离"纹理/采样"因素（平面形状）
        if (mode == 1 || mode == 2) {
            float[] flatFinal = offsetVerts(applyTilt(screen, verts), offX, offY, offZ);
            renderSolidColor(submitCollector, poseStack, flatFinal, 255, 255, 255, mode == 1, cameraPos);
            return;
        }

        // 视频像素已强制不透明。默认（mode 0/3）走【不透明实体管线】：
        // entityTranslucent 的半透明混合会让每像素有效透明度 < 1，视频面片会
        // 漏出背后世界——表现为白屏上"不规律网格+细线"噪点（随视角变化）。
        // 不透明管线经实测无噪点且亮度正常（debugRenderMode=3 验证）。
        boolean opaque = mode != 4; // mode 4 保留半透明单面供对比调试
        RenderType renderType = opaque ? videoOpaqueRenderType(textureId) : videoRenderType(textureId);
        boolean singleFace = mode == 4;

        // 曲面屏：网格化弧面提交（mode 4 调试单面仍走平面路径）
        if (curved && opaque && !singleFace) {
            submitGridTextured(submitCollector, poseStack, renderType,
                    new GridData(displayVerts, curvedGrid.cols, curvedGrid.rows,
                            curvedGrid.uEdge, curvedGrid.vEdge), brightness);
            return;
        }

        final float[] fVerts = displayVerts;
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

    // ───────────── 拾取表面（供"按 V 打开控制"判定，与渲染几何一致） ─────────────

    /** 与渲染一致的实际显示表面（含 displayScale/曲面/倾斜，不含相机外推）。 */
    public static final class PickSurface {
        public final float[] verts;
        public final int cols;   // 分段数（平面屏 = 1）
        public final int rows;
        public final boolean curved;

        PickSurface(float[] verts, int cols, int rows, boolean curved) {
            this.verts = verts;
            this.cols = cols;
            this.rows = rows;
            this.curved = curved;
        }
    }

    /** 重建屏幕实际显示表面（平面屏 = 4 角，曲面屏 = 网格）。 */
    public static PickSurface pickSurface(CinemaScreen screen) {
        float[] base = computeVerts(screen);
        if (base == null) return null;
        float scale = Math.max(0.25f, screen.displayScalePercent() / 100.0f);
        if (Math.abs(scale - 1.0f) > 0.001f) {
            base = scaleVerts(base, scale);
        }
        GridData g = screen.isCurved() ? computeCurvedGrid(screen, base) : null;
        float[] verts = g != null ? g.verts : base;
        verts = applyTilt(screen, verts);
        if (g != null) {
            return new PickSurface(verts, g.cols, g.rows, true);
        }
        return new PickSurface(verts, 1, 1, false);
    }

    // ───────────── 曲面网格（平面屏不触发，零开销） ─────────────

    /** 曲面网格顶点（按行优先存储）与行列分段数。 */
    private static final class GridData {
        final float[] verts;   // 每顶点 3 个 float
        final int cols;        // 水平分段数
        final int rows;        // 垂直分段数
        /** 每列边界对应的 u（按弧角均匀贴图，null = 弦向线性）。 */
        final float[] uEdge;
        /** 每行边界对应的 v（按弧角均匀贴图，null = 线性）。 */
        final float[] vEdge;

        GridData(float[] verts, int cols, int rows) {
            this(verts, cols, rows, null, null);
        }

        GridData(float[] verts, int cols, int rows, float[] uEdge, float[] vEdge) {
            this.verts = verts;
            this.cols = cols;
            this.rows = rows;
            this.uEdge = uEdge;
            this.vEdge = vEdge;
        }

        int gw() { return cols + 1; }
        int gh() { return rows + 1; }
        int idx(int row, int col) { return (row * gw() + col) * 3; }
    }

    /**
     * 由"平面基准四角"（已缩放+相机外推）生成可分离双轴弧面网格：
     * 平面两轴上坐标线性保持，沿法线叠加 sagX(水平)+sagY(垂直) 弯曲。
     * 凸/凹由类型决定；边缘落在原角点上、中心外凸/内凹。
     *
     * @return 网格数据；不适用（平面/参数无效）时返回 null（调用方回退平面路径）
     */
    private static GridData computeCurvedGrid(CinemaScreen screen, float[] base) {
        int type = screen.curvatureType();
        if (type <= 0 || base == null || base.length < 12) return null;
        int[] axes = switch (screen.orientation()) {
            case AXIS_Z -> new int[]{0, 1, 2};       // 水平=x 垂直=y 法线=z
            case AXIS_X -> new int[]{2, 1, 0};       // 水平=z 垂直=y 法线=x
            case AXIS_Y -> new int[]{0, 2, 1};       // 水平=x 垂直=z 法线=y
        };
        int aIdx = axes[0], bIdx = axes[1], nIdx = axes[2];
        double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE;
        double minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
        double baseN = 0;
        for (int i = 0; i < 12; i += 3) {
            minA = Math.min(minA, base[i + aIdx]);
            maxA = Math.max(maxA, base[i + aIdx]);
            minB = Math.min(minB, base[i + bIdx]);
            maxB = Math.max(maxB, base[i + bIdx]);
            baseN += base[i + nIdx];
        }
        baseN /= 4.0;
        double wA = maxA - minA;
        double wB = maxB - minB;
        // 四边独立弧度（度，0..90：超过 90° 视觉不再增强且侧边会折叠）
        int degL = Math.max(0, Math.min(90, screen.curvDegL()));
        int degR = Math.max(0, Math.min(90, screen.curvDegR()));
        int degT = Math.max(0, Math.min(90, screen.curvDegT()));
        int degB = Math.max(0, Math.min(90, screen.curvDegB()));
        boolean horiz = (type == 1 || type == 2 || type == 3 || type == 4)
                && (degL > 0 || degR > 0) && wA > 0.001;
        boolean vert = (type == 3 || type == 4)
                && (degT > 0 || degB > 0) && wB > 0.001;
        if (!horiz && !vert) return null;
        boolean wrapH = type == 2 || type == 4;   // 凹：边缘向观众弯
        boolean wrapV = type == 4;

        int cols = horiz ? gridSegments(wA, Math.max(degL, degR), 150) : 1;
        int rows = vert ? gridSegments(wB, Math.max(degT, degB), 96) : 1;
        int gw = cols + 1, gh = rows + 1;
        double[] sagX = axisSag(gw, wA, degL, degR, wrapH);
        double[] sagY = axisSag(gh, wB, degB, degT, wrapV); // 下端角=degB，上端=degT
        float[] out = new float[gw * gh * 3];
        int oi = 0;
        for (int r = 0; r < gh; r++) {
            double posB = minB + (r / (double) rows) * wB;
            for (int c = 0; c < gw; c++) {
                double posA = minA + (c / (double) cols) * wA;
                // 双轴弧度叠加
                double sag = sagX[c] + sagY[r];
                double n = baseN + sag;
                out[oi + aIdx] = (float) posA;
                out[oi + bIdx] = (float) posB;
                out[oi + nIdx] = (float) n;
                oi += 3;
            }
        }
        // 按弧角均匀的 UV 边界（侧边不再把整段画面压进一条缝）
        float[] uEdge = null;
        float[] vEdge = null;
        if (horiz) {
            double[] phi = axisPhi(gw, wA, degL, degR);
            double total = Math.toRadians(degL) + Math.toRadians(degR);
            double aL = Math.toRadians(degL);
            if (total > 1e-9) {
                uEdge = new float[gw];
                for (int i = 0; i < gw; i++) {
                    uEdge[i] = (float) ((phi[i] + aL) / total);
                }
            }
        }
        if (vert) {
            double[] phi = axisPhi(gh, wB, degB, degT);
            double total = Math.toRadians(degB) + Math.toRadians(degT);
            double aB = Math.toRadians(degB);
            if (total > 1e-9) {
                vEdge = new float[gh];
                for (int i = 0; i < gh; i++) {
                    // 底部(v=1) 对应下弧端
                    vEdge[i] = 1f - (float) ((phi[i] + aB) / total);
                }
            }
        }
        return new GridData(out, cols, rows, uEdge, vEdge);
    }

    /** 网格分段数：兼顾弦长（~2 格/段）与角度分辨率（~15°/段），封顶 cap。 */
    private static int gridSegments(double chord, int maxDeg, int cap) {
        int byChord = (int) Math.ceil(chord / 2.0);
        int byAngle = (int) Math.ceil(maxDeg / 15.0);
        return (int) Math.min(cap, Math.max(2, Math.max(byChord, byAngle)));
    }

    /**
     * 单轴非对称弧面轮廓：两端角度可不同（deg0=小坐标端、deg1=大坐标端）。
     * 圆弧半径 R = 弦长/(sin a0 + sin a1)，弧轴心偏移 R·sin a0；
     * 凸：z = R(cosφ − cos(max(a0,a1)))，最大角度端贴平、其余端抬升；
     * 凹(包裹)：z = R(1 − cosφ)，中心贴平、两端向观众抬起。
     */
    private static double[] axisSag(int points, double chord, int deg0, int deg1, boolean wrap) {
        double[] out = new double[points];
        double a0 = Math.toRadians(Math.min(90, deg0));
        double a1 = Math.toRadians(Math.min(90, deg1));
        double s0 = Math.sin(a0), s1 = Math.sin(a1);
        double sum = s0 + s1;
        if (sum < 1e-3) return out;                 // 两端都不弯
        double r = chord / sum;
        double center = r * s0;                     // 弧心轴位置（自小坐标端起算）
        double cosMax = Math.cos(Math.max(a0, a1));
        for (int i = 0; i < points; i++) {
            double t = i / (double) (points - 1);
            double xLocal = t * chord - center;
            double ratio = Math.max(-1.0, Math.min(1.0, xLocal / r));
            double phi = Math.asin(ratio);
            out[i] = wrap ? r * (1.0 - Math.cos(phi))
                          : r * (Math.cos(phi) - cosMax);
        }
        return out;
    }

    /** 与 axisSag 相同的弧参数，但返回每个点的角度 φ（供弧角均匀贴图）。 */
    private static double[] axisPhi(int points, double chord, int deg0, int deg1) {
        double[] out = new double[points];
        double a0 = Math.toRadians(Math.min(90, deg0));
        double a1 = Math.toRadians(Math.min(90, deg1));
        double s0 = Math.sin(a0), s1 = Math.sin(a1);
        double sum = s0 + s1;
        if (sum < 1e-3) return out;
        double r = chord / sum;
        double center = r * s0;
        for (int i = 0; i < points; i++) {
            double t = i / (double) (points - 1);
            double xLocal = t * chord - center;
            double ratio = Math.max(-1.0, Math.min(1.0, xLocal / r));
            out[i] = Math.asin(ratio);
        }
        return out;
    }

    /** 曲面网格：视频纹理双面提交（26.2 ENTITY 全属性格式，全亮度）。
     *  UV 与角点一一绑定：u/v 按弧角均匀分布（无弧的轴回退线性），
     *  底部行 v=1、顶部行 v=0；背面 = 同一角点取 1-u（水平镜像）。 */
    private static void submitGridTextured(SubmitNodeCollector sc, PoseStack pose, RenderType type,
                                           GridData grid, int brightness) {
        sc.order(0).submitCustomGeometry(pose, type, (ps, vc) -> {
            for (int r = 0; r < grid.rows; r++) {
                float u0, u1, vB, vT;
                for (int c = 0; c < grid.cols; c++) {
                    if (grid.uEdge != null) {
                        u0 = grid.uEdge[c];
                        u1 = grid.uEdge[c + 1];
                    } else {
                        u0 = c / (float) grid.cols;
                        u1 = (c + 1) / (float) grid.cols;
                    }
                    if (grid.vEdge != null) {
                        vB = grid.vEdge[r];
                        vT = grid.vEdge[r + 1];
                    } else {
                        vB = 1f - r / (float) grid.rows;
                        vT = 1f - (r + 1) / (float) grid.rows;
                    }
                    int bl = grid.idx(r, c);
                    int br = grid.idx(r, c + 1);
                    int tr = grid.idx(r + 1, c + 1);
                    int tl = grid.idx(r + 1, c);
                    int[] corners = {bl, br, tr, tl};             // 0..3 = BL BR TR TL
                    float[] cornerUvFront = {u0, vB, u1, vB, u1, vT, u0, vT};
                    float[] cornerUvBack = {1f - u0, vB, 1f - u1, vB, 1f - u1, vT, 1f - u0, vT};
                    int[][] orders = {{0, 1, 2, 3}, {3, 2, 1, 0}}; // 正面 / 背面
                    for (int f = 0; f < 2; f++) {
                        float[] uvOfCorner = f == 0 ? cornerUvFront : cornerUvBack;
                        for (int k = 0; k < 4; k++) {
                            int corner = orders[f][k];
                            int vi = corners[corner];
                            vc.addVertex(ps, grid.verts[vi], grid.verts[vi + 1], grid.verts[vi + 2])
                                    .setColor(brightness, brightness, brightness, 255)
                                    .setUv(uvOfCorner[corner * 2], uvOfCorner[corner * 2 + 1])
                                    .setUv1(0, 10)
                                    .setUv2(240, 240)
                                    .setNormal(0, 1, 0);
                        }
                    }
                }
            }
        });
    }

    /** 曲面网格：纯色双面提交（黑色背板用，debugQuads 管线仅颜色）。 */
    private static void submitGridColor(SubmitNodeCollector sc, PoseStack pose, GridData grid,
                                        int r, int g, int b, int a) {
        int gw = grid.gw();
        RenderType type = RenderTypes.debugQuads();
        sc.order(0).submitCustomGeometry(pose, type, (ps, vc) -> {
            for (int row = 0; row < grid.rows; row++) {
                for (int col = 0; col < grid.cols; col++) {
                    int bl = grid.idx(row, col);
                    int br = grid.idx(row, col + 1);
                    int tr = grid.idx(row + 1, col + 1);
                    int tl = grid.idx(row + 1, col);
                    int[] idx = {bl, br, tr, tl};
                    int[][] orders = {{0, 1, 2, 3}, {1, 0, 3, 2}};
                    for (int[] ord : orders) {
                        for (int k = 0; k < 4; k++) {
                            int vi = idx[ord[k]];
                            vc.addVertex(ps, grid.verts[vi], grid.verts[vi + 1], grid.verts[vi + 2])
                                    .setColor(r, g, b, a);
                        }
                    }
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

    /** 所有顶点统一平移（相机外推）。 */
    private static float[] offsetVerts(float[] verts, float ox, float oy, float oz) {
        if (verts == null) return verts;
        float[] out = new float[verts.length];
        for (int i = 0; i < verts.length; i += 3) {
            out[i] = verts[i] + ox;
            out[i + 1] = verts[i + 1] + oy;
            out[i + 2] = verts[i + 2] + oz;
        }
        return out;
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

    /**
     * 屏幕自身倾斜（绕中心旋转）：左右绕屏幕纵向轴（b），上下绕横向轴（a）。
     * 任意顶点数（平面 4 角或曲面网格）通用；角度为 0 时原样返回。
     */
    private static float[] applyTilt(CinemaScreen screen, float[] verts) {
        int h = screen.tiltDegH();
        int v = screen.tiltDegV();
        if ((h == 0 && v == 0) || verts == null || verts.length < 12 || verts.length % 3 != 0) {
            return verts;
        }
        int[] axes = switch (screen.orientation()) {
            case AXIS_Z -> new int[]{0, 1, 2};       // 横向 x 纵向 y 法线 z
            case AXIS_X -> new int[]{2, 1, 0};       // 横向 z 纵向 y 法线 x
            case AXIS_Y -> new int[]{0, 2, 1};       // 横向 x 纵向 z 法线 y
        };
        int aIdx = axes[0], bIdx = axes[1], nIdx = axes[2];
        int count = verts.length / 3;
        double[] c = new double[3];
        for (int i = 0; i < count; i++) {
            c[0] += verts[i * 3];
            c[1] += verts[i * 3 + 1];
            c[2] += verts[i * 3 + 2];
        }
        c[0] /= count;
        c[1] /= count;
        c[2] /= count;

        double radH = Math.toRadians(h);
        double radV = Math.toRadians(v);
        double ch = Math.cos(radH), sh = Math.sin(radH);
        double cv = Math.cos(radV), sv = Math.sin(radV);

        float[] out = new float[verts.length];
        for (int i = 0; i < count; i++) {
            double x = verts[i * 3 + aIdx] - c[aIdx];   // 横向偏移
            double y = verts[i * 3 + bIdx] - c[bIdx];   // 纵向偏移
            double z = verts[i * 3 + nIdx] - c[nIdx];   // 法线偏移
            // 绕 b（纵向）轴转 h°：左右倾斜（横向-法线平面）
            double x1 = x * ch + z * sh;
            double z1 = -x * sh + z * ch;
            // 绕 a（横向）轴转 v°：上下俯仰（纵向-法线平面）
            double y2 = y * cv - z1 * sv;
            double z2 = y * sv + z1 * cv;
            out[i * 3 + aIdx] = (float) (c[aIdx] + x1);
            out[i * 3 + bIdx] = (float) (c[bIdx] + y2);
            out[i * 3 + nIdx] = (float) (c[nIdx] + z2);
        }
        return out;
    }
}
