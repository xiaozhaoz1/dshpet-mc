package com.github.xiaozhaoz1.dshpet.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ImageResampler} 单测 —— 纯逻辑（方案 B 的核心，必须能自测）。
 *
 * <p>覆盖：尺寸正确性、同尺寸恒等、非法入参兜底、放大/缩小不产生越界与全透明、
 * alpha 预乘（避免暗边）、粒度吸附、以及"真实素材尺寸"的端到端重采样。</p>
 */
class ImageResamplerTest {

    /** 造一帧：全部不透明 + 指定纯色。 */
    private static byte[] solid(int w, int h, int r, int g, int b, int a) {
        byte[] out = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            out[i * 4] = (byte) r;
            out[i * 4 + 1] = (byte) g;
            out[i * 4 + 2] = (byte) b;
            out[i * 4 + 3] = (byte) a;
        }
        return out;
    }

    @Test
    @DisplayName("同尺寸 ⇒ 内容不变")
    void identity() {
        byte[] src = solid(8, 6, 10, 20, 30, 255);
        byte[] dst = ImageResampler.resample(src, 8, 6, 8, 6, ImageResampler.Mode.BICUBIC);
        assertArrayEquals(src, dst);
    }

    @Test
    @DisplayName("非法入参 ⇒ 返回 null（由调用方兜底，不抛）")
    void invalidInputs() {
        assertNull(ImageResampler.resample(null, 8, 6, 4, 3, ImageResampler.Mode.BILINEAR));
        assertNull(ImageResampler.resample(new byte[8 * 6 * 4], 0, 6, 4, 3, ImageResampler.Mode.BILINEAR));
        assertNull(ImageResampler.resample(new byte[8 * 6 * 4], 8, 6, 0, 3, ImageResampler.Mode.BILINEAR));
        assertNull(ImageResampler.resample(new byte[4], 8, 6, 4, 3, ImageResampler.Mode.BILINEAR),
                "源字节不足应兜底");
    }

    @Test
    @DisplayName("纯色放大/缩小 ⇒ 颜色与 alpha 守恒（不许变暗或变透明）")
    void solidColorIsPreserved() {
        byte[] src = solid(10, 10, 200, 100, 50, 255);
        for (ImageResampler.Mode mode : ImageResampler.Mode.values()) {
            byte[] up = ImageResampler.resample(src, 10, 10, 17, 23, mode);
            assertNotNull(up);
            assertEquals(17 * 23 * 4, up.length);
            for (int i = 0; i < 17 * 23; i++) {
                assertEquals(200, up[i * 4] & 0xFF, mode + " 放大后 R 应守恒");
                assertEquals(100, up[i * 4 + 1] & 0xFF, mode + " 放大后 G 应守恒");
                assertEquals(255, up[i * 4 + 3] & 0xFF, mode + " 放大后 A 应守恒");
            }
            byte[] down = ImageResampler.resample(src, 10, 10, 4, 6, mode);
            assertNotNull(down);
            for (int i = 0; i < 4 * 6; i++) {
                assertEquals(200, down[i * 4] & 0xFF, mode + " 缩小后 R 应守恒");
                assertEquals(255, down[i * 4 + 3] & 0xFF, mode + " 缩小后 A 应守恒");
            }
        }
    }

    @Test
    @DisplayName("全透明帧 ⇒ 输出仍全透明（不得凭空冒出颜色）")
    void fullyTransparentStaysTransparent() {
        byte[] src = new byte[6 * 6 * 4]; // 全 0
        byte[] dst = ImageResampler.resample(src, 6, 6, 11, 13, ImageResampler.Mode.BICUBIC);
        assertNotNull(dst);
        for (int i = 0; i < 11 * 13; i++) {
            assertEquals(0, dst[i * 4 + 3] & 0xFF, "alpha 必须仍为 0");
        }
    }

    @Test
    @DisplayName("预乘 alpha: 不透明红 + 透明背景 ⇒ 边缘不得出现暗色（无黑边）")
    void premultipliedAlphaAvoidsDarkFringe() {
        // 左半红（不透明）、右半全透明
        int w = 8, h = 2;
        byte[] src = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                boolean red = x < w / 2;
                src[i] = (byte) (red ? 255 : 0);
                src[i + 3] = (byte) (red ? 255 : 0);
            }
        }
        byte[] dst = ImageResampler.resample(src, w, h, w * 3, h * 3, ImageResampler.Mode.BILINEAR);
        assertNotNull(dst);
        // 过渡带上的像素：R 应接近 255（而非被 0 拉黑），alpha 介于中间
        for (int i = 0; i < w * 3 * h * 3; i++) {
            int a = dst[i * 4 + 3] & 0xFF;
            int r = dst[i * 4] & 0xFF;
            if (a > 30 && a < 225) {
                assertTrue(r >= 200, () -> "过渡像素 R 不该被拉黑（预乘失效？）a=" + a + " r=" + r);
            }
        }
    }

    @Test
    @DisplayName("NEAREST: 放大后每个目标像素取最近源像素（锐利语义）")
    void nearestPicksExactSourcePixels() {
        // 2x1：左黑右白
        byte[] src = new byte[] {0, 0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255};
        byte[] dst = ImageResampler.resample(src, 2, 1, 4, 1, ImageResampler.Mode.NEAREST);
        assertNotNull(dst);
        assertEquals(0, dst[0] & 0xFF, "左侧应保持黑");
        assertEquals(255, dst[(4 - 1) * 4] & 0xFF, "最右应保持白");
    }

    @Test
    @DisplayName("真实素材尺寸: 72x92 → 94x120（用户环境）不崩且输出合法")
    void realAssetSize() {
        byte[] src = solid(72, 92, 120, 90, 200, 255);
        for (ImageResampler.Mode mode : ImageResampler.Mode.values()) {
            byte[] dst = ImageResampler.resample(src, 72, 92, 94, 120, mode);
            assertNotNull(dst);
            assertEquals(94 * 120 * 4, dst.length);
        }
    }

    @Test
    @DisplayName("粒度吸附: 吸附到 guiScale 的整数倍（1:1 绘制的前提），且不小于 1")
    void snapToGranularity() {
        assertEquals(94, ImageResampler.snapToGranularity(94f, 2), "94 已是 2 的倍数 ⇒ 不变");
        assertEquals(120, ImageResampler.snapToGranularity(120f, 2));
        assertEquals(94, ImageResampler.snapToGranularity(93f, 2), "93 → 最近偶数 94");
        assertEquals(92, ImageResampler.snapToGranularity(93f, 4), "93 → 最近 4 的倍数 92");
        assertEquals(2, ImageResampler.snapToGranularity(1f, 2), "不得吸到 0");
        assertEquals(1, ImageResampler.snapToGranularity(1f, 1), "粒度 1 ⇒ 取整");
        assertEquals(7, ImageResampler.snapToGranularity(6.6f, 1));
    }
}
