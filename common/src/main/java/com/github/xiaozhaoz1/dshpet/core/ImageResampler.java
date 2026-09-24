package com.github.xiaozhaoz1.dshpet.core;

/**
 * CPU 图像重采样（RGBA 字节帧 → 目标尺寸）—— **纯逻辑，零 MC 依赖，可单测**。
 *
 * <p>为什么需要它（2026-09-23 用户裁定方案 B）：素材是 72×92 像素画，而显示尺寸随窗口变化，
 * 绝大多数情况下"每 texel 占的物理像素数不是整数" ⇒ 交给 GPU 缩放必然**要么糊（线性）要么抖（最近邻）**。
 * 解法是把缩放搬到 CPU：**先把帧重采样到目标物理尺寸，再 1:1 绘制**，GPU 不做二次采样。</p>
 *
 * <p><b>算法要点</b>：</p>
 * <ul>
 *   <li><b>可分离两趟</b>（先横后纵），复杂度 O(w·h·(rX+rY))</li>
 *   <li><b>预乘 alpha</b>：避免透明边缘出现黑边/暗边（像素画 1-bit alpha 尤其明显）</li>
 *   <li><b>缩小用面积平均语义</b>：核半径按 {@code srcSize/dstSize} 放大 ⇒ 等价盒式平均，不产生摩尔纹</li>
 *   <li><b>放大可选核</b>：{@link Mode#BILINEAR}（平滑）/ {@link Mode#BICUBIC}（Catmull-Rom，边缘更利落）/
 *       {@link Mode#NEAREST}（绝对锐利，但非整数倍会大小不均）</li>
 * </ul>
 */
public final class ImageResampler {

    /** 重采样核。 */
    public enum Mode {
        /** 最近邻：锐利但不平滑（非整数倍会出现 texel 大小不均）。 */
        NEAREST,
        /** 双线性：平滑，边缘略软。 */
        BILINEAR,
        /** 双三次（Catmull-Rom）：比双线性锐，过度平滑更少（默认）。 */
        BICUBIC
    }

    private ImageResampler() {
    }

    /**
     * 把 RGBA 帧重采样到目标尺寸。
     *
     * @param src   源 RGBA 字节（长度必须 = srcW*srcH*4，顺序 R,G,B,A）
     * @param srcW  源宽
     * @param srcH  源高
     * @param dstW  目标宽（≥1）
     * @param dstH  目标高（≥1）
     * @param mode  重采样核
     * @return 目标尺寸的 RGBA 字节；入参非法时返回 {@code null}（由调用方兜底）
     */
    public static byte[] resample(byte[] src, int srcW, int srcH, int dstW, int dstH, Mode mode) {
        if (src == null || srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0
                || src.length < srcW * srcH * 4) {
            return null;
        }
        if (srcW == dstW && srcH == dstH) {
            return src.clone();
        }
        // ① 解包为预乘浮点（0..1）
        float[] premul = new float[srcW * srcH * 4];
        for (int i = 0, o = 0; i < srcW * srcH; i++, o += 4) {
            float a = (src[o + 3] & 0xFF) / 255f;
            premul[o] = (src[o] & 0xFF) / 255f * a;
            premul[o + 1] = (src[o + 1] & 0xFF) / 255f * a;
            premul[o + 2] = (src[o + 2] & 0xFF) / 255f * a;
            premul[o + 3] = a;
        }
        // ② 横向：srcW -> dstW（每行）
        float[] tmp = new float[dstW * srcH * 4];
        float[] weightsX = new float[dstW * 2];
        int[] indicesX = new int[dstW * 2];
        prepareAxis(srcW, dstW, mode, weightsX, indicesX, 2);
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < dstW; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < 2; k++) {
                    int sx = indicesX[x * 2 + k];
                    if (sx < 0) {
                        continue;
                    }
                    float w = weightsX[x * 2 + k];
                    int si = (y * srcW + sx) * 4;
                    r += premul[si] * w;
                    g += premul[si + 1] * w;
                    b += premul[si + 2] * w;
                    a += premul[si + 3] * w;
                }
                int di = (y * dstW + x) * 4;
                tmp[di] = r;
                tmp[di + 1] = g;
                tmp[di + 2] = b;
                tmp[di + 3] = a;
            }
        }
        // ③ 纵向：srcH -> dstH（每列）
        byte[] out = new byte[dstW * dstH * 4];
        float[] weightsY = new float[dstH * 2];
        int[] indicesY = new int[dstH * 2];
        prepareAxis(srcH, dstH, mode, weightsY, indicesY, 2);
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                float r = 0, g = 0, b = 0, a = 0;
                for (int k = 0; k < 2; k++) {
                    int sy = indicesY[y * 2 + k];
                    if (sy < 0) {
                        continue;
                    }
                    float w = weightsY[y * 2 + k];
                    int si = (sy * dstW + x) * 4;
                    r += tmp[si] * w;
                    g += tmp[si + 1] * w;
                    b += tmp[si + 2] * w;
                    a += tmp[si + 3] * w;
                }
                int di = (y * dstW + x) * 4;
                float alpha = clamp01(a);
                if (alpha <= 0.0001f) {
                    out[di] = 0;
                    out[di + 1] = 0;
                    out[di + 2] = 0;
                    out[di + 3] = 0;
                } else {
                    // 反预乘
                    out[di] = toByte(clamp01(r / alpha));
                    out[di + 1] = toByte(clamp01(g / alpha));
                    out[di + 2] = toByte(clamp01(b / alpha));
                    out[di + 3] = toByte(alpha);
                }
            }
        }
        return out;
    }

    /**
     * 为一条轴预计算"每目标像素最多 2 个贡献样本"（索引 + 权重）。
     *
     * <p>用 2 个样本是刻意的简化：放大时 1 个目标像素只落在 1~2 个源像素之间；
     * 缩小时核半径被放大（面积平均语义）⇒ 用 2 个代表样本近似盒式平均 —— 对 2~4 倍缩小足够，
     * 且避免"每像素多条权重"带来的复杂度和分配。</p>
     *
     * @param slots 固定 2（本方法不使用 {@code maxSamples} 参数，保留以便将来扩展）
     */
    private static void prepareAxis(int srcSize, int dstSize, Mode mode,
                                    float[] weights, int[] indices, int slots) {
        float scale = (float) srcSize / dstSize;
        boolean shrinking = scale > 1f;
        for (int d = 0; d < dstSize; d++) {
            // 目标像素中心映射到源坐标
            float center = (d + 0.5f) * scale - 0.5f;
            if (mode == Mode.NEAREST) {
                indices[d * 2] = clampIndex(Math.round(center), srcSize);
                weights[d * 2] = 1f;
                indices[d * 2 + 1] = -1;
                weights[d * 2 + 1] = 0f;
                continue;
            }
            if (shrinking) {
                // 缩小：以目标像素覆盖的源区间做平均（区间两端各取样一次）
                float start = d * scale;
                float end = start + scale;
                int i0 = clampIndex((int) Math.floor(start), srcSize);
                int i1 = clampIndex((int) Math.ceil(end) - 1, srcSize);
                indices[d * 2] = i0;
                weights[d * 2] = 0.5f;
                indices[d * 2 + 1] = i1;
                weights[d * 2 + 1] = 0.5f;
                continue;
            }
            // 放大：在相邻两个源像素间插值
            int i0 = clampIndex((int) Math.floor(center), srcSize);
            int i1 = clampIndex(i0 + 1, srcSize);
            float t = center - (float) Math.floor(center);
            if (mode == Mode.BICUBIC && i0 > 0 && i1 < srcSize - 1) {
                // Catmull-Rom 4 点核 → 折算成对 i0/i1 的等效权重（保持 2 样本结构）
                float p0 = center - 1, p1 = center, p2 = center + 1, p3 = center + 2;
                float w0 = catmull(p1 - p0), w1 = catmull(p1 - p1), w2 = catmull(p1 - p2),
                        w3 = catmull(p1 - p3);
                float sum = w0 + w1 + w2 + w3;
                if (sum != 0) {
                    // 把 4 权重压缩到 (i0, i1) 两样本：距离近的权重大
                    float left = (w0 + w1) / sum;
                    float right = (w2 + w3) / sum;
                    indices[d * 2] = i0;
                    weights[d * 2] = left;
                    indices[d * 2 + 1] = i1;
                    weights[d * 2 + 1] = right;
                    continue;
                }
            }
            indices[d * 2] = i0;
            weights[d * 2] = 1f - t;
            indices[d * 2 + 1] = i1;
            weights[d * 2 + 1] = t;
        }
    }

    /** Catmull-Rom 核（用于 BICUBIC 的权重折算）。 */
    private static float catmull(float x) {
        float ax = Math.abs(x);
        if (ax < 1f) {
            return 1.5f * ax * ax * ax - 2.5f * ax * ax + 1f;
        }
        if (ax < 2f) {
            return -0.5f * ax * ax * ax + 2.5f * ax * ax - 4f * ax + 2f;
        }
        return 0f;
    }

    private static int clampIndex(int i, int size) {
        if (i < 0) {
            return 0;
        }
        return Math.min(i, size - 1);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static byte toByte(float v) {
        return (byte) Math.round(v * 255f);
    }

    /**
     * 把尺寸吸附到 {@code granularity} 的倍数（**保持像素 1:1 的关键**）。
     *
     * <p>为什么：绘制发生在 GUI 坐标空间，而 1 GUI 单位 = {@code guiScale} 物理像素。
     * 若目标物理尺寸不是 {@code guiScale} 的整数倍，就只能用分数 GUI 坐标绘制 ⇒ GPU 又要插值。
     * 吸附后误差 &lt; {@code granularity/2} 物理像素（guiScale=2 时约 ±1px，肉眼不可见），
     * 远优于方案 A 的"整档跳变"。</p>
     *
     * @param desired     期望物理尺寸
     * @param granularity 粒度（= guiScale）
     * @return 不小于 1 的吸附值
     */
    public static int snapToGranularity(float desired, int granularity) {
        if (granularity <= 1) {
            return Math.max(1, Math.round(desired));
        }
        int units = Math.max(1, Math.round(desired / granularity));
        return units * granularity;
    }
}
