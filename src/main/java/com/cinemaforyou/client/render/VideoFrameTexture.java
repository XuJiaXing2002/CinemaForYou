package com.cinemaforyou.client.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.texture.AbstractTexture;

/**
 * 视频动态纹理：完整 mip 链 + 三线性 CLAMP 采样。
 *
 * <p>原版 {@code DynamicTexture} 只有单层纹理且采样器是 NEAREST/REPEAT：
 * 屏幕稍远/斜视时一个像素覆盖不到一个纹素，点采样会随机跳过纹素，
 * 表现为"黑色噪点 + 随视角爬行闪烁"（中心最清楚、越靠屏幕边缘越严重）。
 * 本类为每帧提供逐级减半的 mip 层并用线性+mipmap 采样，缩小采样时平滑取均值。
 *
 * <p>用法（均在渲染线程）：
 * <ol>
 *   <li>{@link #init(int, int)} 按视频尺寸创建 GPU 纹理与各级 CPU 缓冲；</li>
 *   <li>每帧把 ABGR 像素写入各级 {@link NativeImage}（0 级为完整帧，
 *       1..n 级由解码线程预生成的减半帧）；</li>
 *   <li>{@link #uploadAll()} 一次性提交所有层到 GPU。</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public class VideoFrameTexture extends AbstractTexture {

    private final String label;
    private NativeImage[] levels = new NativeImage[0];

    public VideoFrameTexture(String label) {
        this.label = label;
    }

    /**
     * 视频尺寸 → mip 层数。
     *
     * <p>只生成到<strong>较小边</strong>缩到 1 为止：超宽内容（如 21:9）按最大边
     * 生成会在末级出现"短边为 0"的退化层，与 GPU 纹理尺寸不一致导致
     * {@code Dest texture(...) is not large enough} 崩溃。
     */
    public static int mipLevelCount(int width, int height) {
        int minDim = Math.min(width, height);
        int count = 1;
        while (minDim > 1) {
            minDim >>= 1;
            count++;
        }
        return count;
    }

    /** 渲染线程：创建 GPU 纹理与各级缓冲（0 级尺寸 = 视频帧）。 */
    public void init(int width, int height) {
        int mipLevels = mipLevelCount(width, height);
        NativeImage[] imgs = new NativeImage[mipLevels];
        for (int k = 0; k < mipLevels; k++) {
            imgs[k] = new NativeImage(
                    Math.max(1, width >> k), Math.max(1, height >> k), true);
        }
        GpuTexture tex = RenderSystem.getDevice().createTexture(
                label,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                width, height,
                1, mipLevels);
        this.texture = tex;
        this.textureView = RenderSystem.getDevice().createTextureView(tex);
        // CLAMP 防止边缘采样绕回；LINEAR + mipmap = 三线性缩小采样
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR, true);
        this.levels = imgs;
    }

    /** 第 k 级 CPU 缓冲（k=0 为完整帧）。 */
    public NativeImage level(int k) {
        return levels[k];
    }

    public int levelCount() {
        return levels.length;
    }

    public int levelWidth(int k) {
        return levels[k].getWidth();
    }

    public int levelHeight(int k) {
        return levels[k].getHeight();
    }

    /** 渲染线程：把各级缓冲写入 GPU。必须在任何渲染 pass 之外调用。 */
    public void uploadAll() {
        if (this.texture == null) return;
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        for (int k = 0; k < this.levels.length; k++) {
            encoder.writeToTexture(this.texture, this.levels[k], k, 0, 0, 0);
        }
    }

    @Override
    public void close() {
        for (NativeImage img : levels) {
            img.close();
        }
        levels = new NativeImage[0];
        super.close();
    }
}
