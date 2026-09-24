package com.github.xiaozhaoz1.dshpet.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FrameGeometry} 单测 —— 纯 JVM，不碰 MC 类型。
 *
 * <p>这些断言直接对应实机反馈的 bug："宠物显示太大"= 用了画布尺寸而非内容盒。</p>
 */
class FrameGeometryTest {

    /** 构造一帧：在 (x0..x1, y0..y1) 区域内 alpha=255，其余透明。 */
    private static byte[] frame(int w, int h, int x0, int y0, int x1, int y1) {
        byte[] px = new byte[w * h * 4];
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int i = (y * w + x) * 4;
                px[i] = (byte) 0xFF;     // R
                px[i + 3] = (byte) 0xFF; // A
            }
        }
        return px;
    }

    @Test
    @DisplayName("单帧内容盒：只统计非透明像素范围")
    void singleFrameBounds() {
        // 220x124 画布，内容只在 (10,20)-(109,111) ⇒ 100x92
        byte[] f = frame(220, 124, 10, 20, 109, 111);
        FrameGeometry.Bounds b = FrameGeometry.unionBounds(220, 124, List.of(f));
        assertNotNull(b);
        assertEquals(10, b.minX());
        assertEquals(20, b.minY());
        assertEquals(109, b.maxX());
        assertEquals(111, b.maxY());
        assertEquals(100, b.width());
        assertEquals(92, b.height());
    }

    @Test
    @DisplayName("多帧取并集：人物移动/转身时不会裁掉边缘")
    void unionAcrossFrames() {
        byte[] a = frame(100, 100, 10, 10, 49, 59);
        byte[] b = frame(100, 100, 30, 20, 79, 89);
        FrameGeometry.Bounds u = FrameGeometry.unionBounds(100, 100, List.of(a, b));
        assertNotNull(u);
        assertEquals(10, u.minX());
        assertEquals(10, u.minY());
        assertEquals(79, u.maxX());
        assertEquals(89, u.maxY());
    }

    @Test
    @DisplayName("alpha 阈值：恰好等于 8 视为透明，9 视为内容")
    void alphaThresholdIsExclusive() {
        int w = 4;
        int h = 1;
        byte[] px = new byte[w * h * 4];
        // x=0..3 分别 alpha = 0, 8, 9, 255
        px[3] = 0;
        px[7] = 8;
        px[11] = 9;
        px[15] = (byte) 255;
        FrameGeometry.Bounds b = FrameGeometry.unionBounds(w, h, List.of(px));
        assertNotNull(b);
        assertEquals(2, b.minX(), "alpha=8 应视为透明，首个内容像素是 x=2 (alpha=9)");
        assertEquals(3, b.maxX());
        assertEquals(2, b.width());
    }

    @Test
    @DisplayName("全透明素材 → null（调用方需兜底，不得抛异常）")
    void allTransparentYieldsNull() {
        byte[] px = new byte[10 * 10 * 4];
        assertNull(FrameGeometry.unionBounds(10, 10, List.of(px)));
    }

    @Test
    @DisplayName("坏帧（长度不足/null）跳过而非崩溃")
    void skipsMalformedFrames() {
        byte[] good = frame(20, 20, 5, 5, 14, 14);
        FrameGeometry.Bounds b = FrameGeometry.unionBounds(20, 20,
                java.util.Arrays.asList(null, new byte[3], good));
        assertNotNull(b, "应忽略坏帧并仍能算出内容盒");
        assertEquals(5, b.minX());
        assertEquals(14, b.maxX());
    }

    @Test
    @DisplayName("非法尺寸 → null")
    void rejectsInvalidDimensions() {
        assertNull(FrameGeometry.unionBounds(0, 10, List.of(new byte[0])));
        assertNull(FrameGeometry.unionBounds(-1, 10, List.of(new byte[0])));
        assertNull(FrameGeometry.unionBounds(10, 10, null));
    }

    @Test
    @DisplayName("缩放：110px 目标高度 / 92px 内容 = 1.20（实机反馈「太大」的修正值）")
    void scaleFromContentHeight() {
        // 上游实测内容盒高 92 ⇒ 目标 110px ⇒ 1.20（原先按画布 124 算得 1.5 ⇒ 偏大）
        assertEquals(1.196f, FrameGeometry.scaleFor(92, 110f, 0.25f, 4f), 0.01f);
    }

    @Test
    @DisplayName("缩放钳制：极小/极大内容都被夹到范围内")
    void scaleClamping() {
        assertEquals(0.25f, FrameGeometry.scaleFor(1000, 110f, 0.25f, 4f), 0.001f, "内容过大 ⇒ 夹到下界");
        assertEquals(4f, FrameGeometry.scaleFor(1, 110f, 0.25f, 4f), 0.001f, "内容过小 ⇒ 夹到上界");
    }

    @Test
    @DisplayName("缩放兜底：非法入参返回 1.0（不崩、不放大）")
    void scaleFallback() {
        assertEquals(1f, FrameGeometry.scaleFor(0, 110f, 0.25f, 4f), 0.001f);
        assertEquals(1f, FrameGeometry.scaleFor(-5, 110f, 0.25f, 4f), 0.001f);
        assertEquals(1f, FrameGeometry.scaleFor(92, 0f, 0.25f, 4f), 0.001f);
        assertEquals(1f, FrameGeometry.scaleFor(92, 110f, 5f, 1f), 0.001f, "min>max 属非法配置");
    }

    @Test
    @DisplayName("回归: displayScale 不随 GUI Scale/窗口比例变化 —— 曾因「屏高22%封顶」算出 0.61x 只画 44x56px")
    void displayScaleIsAbsoluteNotViewportRelative() {
        // 实测素材: 内容盒高 92, 目标 110px, 绝对边界 [48,240]
        float scale = FrameGeometry.displayScale(92, 110f, 48f, 240f);
        assertEquals(110f, 92 * scale, 0.01f, "绘制高度必须等于目标 110px（绝对像素）");
        assertEquals(1.196f, scale, 0.01f);
        // 关键回归点: 同样的内容盒在任何视口下都得到同一缩放（本方法不接收视口参数 ⇒ 结构上保证）
        assertEquals(scale, FrameGeometry.displayScale(92, 110f, 48f, 240f), 0.0001f,
                "不得引入视口相关的钳制（那是 44x56px 的根因）");
        // 反例保护: 若误用「屏高比例」思路, guiHeight=540 的 22% 会得到 0.61 —— 断言绝不会是那个值
        assertTrue(scale > 1.0f, "正常素材应放大到 1.0x 以上, 而非被压到 0.61x");
    }

    @Test
    @DisplayName("displayScale 的目标值被绝对边界夹住（不是被视口夹住）")
    void displayScaleClampsToAbsoluteBounds() {
        // 目标 20px 低于下限 48 ⇒ 用 48px
        assertEquals(48f, 92 * FrameGeometry.displayScale(92, 20f, 48f, 240f), 0.01f);
        // 目标 9999px 高于上限 240 ⇒ 用 240px
        assertEquals(240f, 92 * FrameGeometry.displayScale(92, 9999f, 48f, 240f), 0.01f);
    }

    @Test
    @DisplayName("displayScale 非法入参兜底 1.0")
    void displayScaleFallback() {
        assertEquals(1f, FrameGeometry.displayScale(0, 110f, 48f, 240f), 0.001f);
        assertEquals(1f, FrameGeometry.displayScale(92, -1f, 48f, 240f), 0.001f);
        assertEquals(1f, FrameGeometry.displayScale(92, 110f, 240f, 48f), 0.001f, "min>max 非法");
    }

    @Test
    @DisplayName("回归: 目标高度按屏高比例（实机取证 guiHeight=255 时固定 110px = 43% 屏高，观感过大）")
    void targetHeightFollowsScreenRatio() {
        // 用户实测环境: guiHeight=255, 比例 0.20 ⇒ 目标 51px（夹到 [48,160] 后仍 51）
        assertEquals(51f, FrameGeometry.targetHeightFor(255f, 0.20f, 48f, 160f), 0.01f);
        // 常见 1080p(GUI Scale 2): guiHeight≈540 ⇒ 目标 108px
        assertEquals(108f, FrameGeometry.targetHeightFor(540f, 0.20f, 48f, 160f), 0.01f);
        // 小窗: guiHeight=200 ⇒ 40px 低于下限 48 ⇒ 取 48（保证可见）
        assertEquals(48f, FrameGeometry.targetHeightFor(200f, 0.20f, 48f, 160f), 0.01f);
        // 超大分辨率: guiHeight=1440 ⇒ 288px 超上限 ⇒ 取 160（防夸张）
        assertEquals(160f, FrameGeometry.targetHeightFor(1440f, 0.20f, 48f, 160f), 0.01f);
    }

    @Test
    @DisplayName("targetHeightFor 非法入参一律兜底 1px（安全值：渲染热路径不抛异常）")
    void targetHeightFallback() {
        // 语义统一: 任何非法入参(屏高/比例/边界非法, 或 min>max)都返回 1px
        assertEquals(1f, FrameGeometry.targetHeightFor(0f, 0.2f, 48f, 160f), 0.01f, "屏高 0");
        assertEquals(1f, FrameGeometry.targetHeightFor(-5f, 0.2f, 48f, 160f), 0.01f, "屏高负");
        assertEquals(1f, FrameGeometry.targetHeightFor(540f, 0f, 48f, 160f), 0.01f, "比例 0");
        assertEquals(1f, FrameGeometry.targetHeightFor(540f, 0.2f, 0f, 160f), 0.01f, "min 0");
        assertEquals(1f, FrameGeometry.targetHeightFor(540f, 0.2f, 160f, 48f), 0.01f, "min>max");
    }

    @Test
    @DisplayName("回归: 角色宽度按屏宽比例（对齐上游 462/1920=24%）—— 用户 GUI 宽 480 ⇒ 115px")
    void targetWidthFollowsScreenRatio() {
        // 用户实测环境: guiWidth=480 ⇒ 目标角色宽 = 480×0.24 = 115px（此前实现给 40px，只有上游的 35%）
        assertEquals(115f, FrameGeometry.targetWidthFor(480f, 0.24f, 64f, 400f), 0.5f);
        // 上游设计窗口 1920×0.24 = 461 ⇒ 被上限 400 夹住（防超大屏宠物夸张）
        assertEquals(400f, FrameGeometry.targetWidthFor(1920f, 0.24f, 64f, 400f), 1f);
        // 常见 1280 宽 GUI(缩放后) ⇒ 307px, 不触发上限 ⇒ 精确比例
        assertEquals(307f, FrameGeometry.targetWidthFor(1280f, 0.24f, 64f, 400f), 0.5f);
        // 下限: 极小屏 200 宽 ⇒ 48px 低于下限 64 ⇒ 取 64（保证可见）
        assertEquals(64f, FrameGeometry.targetWidthFor(200f, 0.24f, 64f, 400f), 0.01f);
        // 上限: 4K 宽 2560 ⇒ 614px 超上限 ⇒ 取 320（防夸张）
        assertEquals(400f, FrameGeometry.targetWidthFor(2560f, 0.24f, 64f, 400f), 0.01f, "4K 宽 614 ⇒ 上限 400");
    }

    @Test
    @DisplayName("targetWidthFor 非法入参兜底 1px")
    void targetWidthFallback() {
        assertEquals(1f, FrameGeometry.targetWidthFor(0f, 0.24f, 64f, 400f), 0.01f);
        assertEquals(1f, FrameGeometry.targetWidthFor(480f, 0f, 64f, 400f), 0.01f);
        assertEquals(1f, FrameGeometry.targetWidthFor(480f, 0.24f, 400f, 64f), 0.01f);
    }

    @Test
    @DisplayName("回归: 双约束取更小者 —— 用户实测屏 480x255 / 内容 72x92 / 宽15% / 高上限25%")
    void scaleForViewportTakesSmallerConstraint() {
        // 约束① 宽: 480×0.15 = 72px ⇒ scale = 72/72 = 1.0
        // 约束② 高: 255×0.25 = 63.75px ⇒ scale = 63.75/92 = 0.693
        // ⇒ 取 0.693 ⇒ 绘制 50x64（占屏高 25%）
        float s = FrameGeometry.scaleForViewport(480, 255, 72, 92, 0.15f, 0.25f, 48f, 320f);
        assertEquals(0.693f, s, 0.005f);
        assertEquals(50f, 72 * s, 1f, "绘制宽应约 50px");
        assertEquals(64f, 92 * s, 1f, "绘制高应约 64px（占屏高 25%）");
    }

    @Test
    @DisplayName("回归: 宽屏下由宽约束生效，窄屏下由高约束生效（两条都不能少）")
    void bothConstraintsNeeded() {
        // 宽屏 1920x540: 宽 1920×0.15=288 ⇒ 288/72=4.0; 高 540×0.25=135 ⇒ 135/92=1.467
        // ⇒ 高约束生效（若只有宽约束会画成 288x368 = 占屏高 68%，明显过大）
        float wide = FrameGeometry.scaleForViewport(1920, 540, 72, 92, 0.15f, 0.25f, 48f, 320f);
        assertEquals(1.467f, wide, 0.01f);
        assertTrue(92 * wide <= 540 * 0.25f + 0.5f, "高不得超过屏高 25%");

        // 近正方形 480x255（用户环境）: 高约束生效
        float square = FrameGeometry.scaleForViewport(480, 255, 72, 92, 0.15f, 0.25f, 48f, 320f);
        assertTrue(92 * square <= 255 * 0.25f + 0.5f, "近正方形下也必须守住高上限");
    }

    @Test
    @DisplayName("scaleForViewport 非法入参兜底 1.0")
    void scaleForViewportFallback() {
        assertEquals(1f, FrameGeometry.scaleForViewport(0, 255, 72, 92, 0.15f, 0.25f, 48f, 320f), 0.001f);
        assertEquals(1f, FrameGeometry.scaleForViewport(480, 0, 72, 92, 0.15f, 0.25f, 48f, 320f), 0.001f);
        assertEquals(1f, FrameGeometry.scaleForViewport(480, 255, 0, 92, 0.15f, 0.25f, 48f, 320f), 0.001f);
        assertEquals(1f, FrameGeometry.scaleForViewport(480, 255, 72, 92, 0f, 0.25f, 48f, 320f), 0.001f);
    }
}
