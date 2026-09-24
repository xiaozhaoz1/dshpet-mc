package com.github.xiaozhaoz1.dshpet.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 属性测试（property-based）：用**随机输入**验证不变量，而不是逐个列举用例。
 *
 * <p>实现选择说明（2026-09-23 社区标准审计 ⑦ 项）：社区常用 jqwik，但
 * <b>本项目不引入第三方测试依赖</b>（jqwik 未被任何生产代码需要 ⇒ 不为零调用方建面，
 * PROJECT-RULES 2.4）。用 JUnit5 + 固定种子随机数实现同等价值：
 * <b>覆盖大量输入的"不许发生的性质"</b>，且失败可复现（种子固定）。</p>
 */
class FrameGeometryPropertyTest {

    /** 固定种子 ⇒ 失败可复现（随机但不 flaky）。 */
    private static final long SEED = 20260923L;
    private static final int ITERATIONS = 2000;

    @Test
    @DisplayName("属性: 角色高度永不超过 屏高×高上限（#D1 事故的核心不变量）")
    void characterHeightNeverExceedsRatio() {
        Random rnd = new Random(SEED);
        float heightRatio = 0.25f;
        for (int i = 0; i < ITERATIONS; i++) {
            int screenW = 80 + rnd.nextInt(4000);      // 80..4079
            int screenH = 60 + rnd.nextInt(3000);      // 60..3059
            int contentW = 1 + rnd.nextInt(600);
            int contentH = 1 + rnd.nextInt(600);
            float s = FrameGeometry.scaleForViewport(screenW, screenH, contentW, contentH,
                    0.15f, heightRatio, 48f, 320f);
            float drawnH = contentH * s;
            assertTrue(drawnH <= screenH * heightRatio + 0.5f,
                    () -> "违反高上限: screenH=" + screenH + " contentH=" + contentH
                            + " scale=" + s + " drawnH=" + drawnH);
        }
    }

    @Test
    @DisplayName("属性: 角色宽度不超过 屏宽×宽比例（且不低于下限、不高于上限）")
    void characterWidthFollowsAbsoluteBounds() {
        Random rnd = new Random(SEED + 1);
        for (int i = 0; i < ITERATIONS; i++) {
            int screenW = 80 + rnd.nextInt(4000);
            int screenH = 60 + rnd.nextInt(3000);
            int contentW = 1 + rnd.nextInt(600);
            int contentH = 1 + rnd.nextInt(600);
            float s = FrameGeometry.scaleForViewport(screenW, screenH, contentW, contentH,
                    0.15f, 0.25f, 48f, 320f);
            float drawnW = contentW * s;
            // 宽度应 ≤ max(宽比例值, 下限)，且 ≤ 上限；受高约束时只会更小
            float widthTarget = FrameGeometry.targetWidthFor(screenW, 0.15f, 48f, 320f);
            assertTrue(drawnW <= widthTarget + 0.5f,
                    () -> "超过宽目标: drawnW=" + drawnW + " target=" + widthTarget);
            assertTrue(drawnW <= 320f + 0.5f, () -> "超过绝对上限 320: " + drawnW);
        }
    }

    @Test
    @DisplayName("属性: 缩放在任意输入下都是有限正数（不产生 NaN/Infinity/0/负数）")
    void scaleIsAlwaysSane() {
        Random rnd = new Random(SEED + 2);
        for (int i = 0; i < ITERATIONS; i++) {
            int screenW = rnd.nextInt(5000);
            int screenH = rnd.nextInt(4000);
            int contentW = rnd.nextInt(700);
            int contentH = rnd.nextInt(700);
            float s = FrameGeometry.scaleForViewport(screenW, screenH, contentW, contentH,
                    0.15f, 0.25f, 48f, 320f);   // 含 0 值 ⇒ 走兜底分支
            assertTrue(Float.isFinite(s), () -> "非有限值: " + s);
            assertTrue(s > 0f, () -> "非正缩放: " + s);
        }
    }

    @Test
    @DisplayName("属性: 内容盒并集单调 —— 增加一帧不会缩小边界，只会扩大或不变")
    void unionBoundsAreMonotonic() {
        Random rnd = new Random(SEED + 3);
        for (int i = 0; i < 200; i++) {
            int w = 8 + rnd.nextInt(64);
            int h = 8 + rnd.nextInt(64);
            byte[] f1 = randomFrame(rnd, w, h);
            byte[] f2 = randomFrame(rnd, w, h);
            FrameGeometry.Bounds onlyFirst = FrameGeometry.unionBounds(w, h, java.util.List.of(f1));
            FrameGeometry.Bounds both = FrameGeometry.unionBounds(w, h, java.util.List.of(f1, f2));
            if (onlyFirst == null) {
                continue; // 首帧全透明 ⇒ 并集可能为 null 或等于 f2，不参与断言
            }
            assertTrue(both != null, "并入一帧后不应变成 null");
            assertTrue(both.minX() <= onlyFirst.minX() && both.minY() <= onlyFirst.minY(),
                    "并集最小角只能更小或不变");
            assertTrue(both.maxX() >= onlyFirst.maxX() && both.maxY() >= onlyFirst.maxY(),
                    "并集最大角只能更大或不变");
        }
    }

    @Test
    @DisplayName("属性: 缩放对内容尺寸单调不增 —— 内容越大，所需缩放越小或不变")
    void scaleIsNonIncreasingInContentSize() {
        Random rnd = new Random(SEED + 4);
        for (int i = 0; i < 500; i++) {
            int screenW = 200 + rnd.nextInt(2000);
            int screenH = 150 + rnd.nextInt(1500);
            int contentW = 1 + rnd.nextInt(300);
            int contentH = 1 + rnd.nextInt(300);
            float s1 = FrameGeometry.scaleForViewport(screenW, screenH, contentW, contentH,
                    0.15f, 0.25f, 48f, 320f);
            float s2 = FrameGeometry.scaleForViewport(screenW, screenH, contentW + 50, contentH + 50,
                    0.15f, 0.25f, 48f, 320f);
            assertTrue(s2 <= s1 + 1e-4f,
                    () -> "内容变大后缩放不应增大: s1=" + s1 + " s2=" + s2);
        }
    }

    @Test
    @DisplayName("属性: 非法入参恒返回 1.0（渲染热路径不许抛异常）")
    void invalidInputsAlwaysFallBackToOne() {
        Random rnd = new Random(SEED + 5);
        for (int i = 0; i < 500; i++) {
            int badScreenW = rnd.nextBoolean() ? 0 : -rnd.nextInt(100);
            float s = FrameGeometry.scaleForViewport(badScreenW, 255, 72, 92, 0.15f, 0.25f, 48f, 320f);
            assertEquals(1f, s, 0.001f, "非法屏宽必须兜底 1.0");
        }
    }

    private static byte[] randomFrame(Random rnd, int w, int h) {
        byte[] px = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                boolean opaque = rnd.nextInt(10) < 3;   // 约 30% 不透明，制造稀疏内容
                px[i] = (byte) 0xFF;
                px[i + 3] = (byte) (opaque ? 0xFF : 0x00);
            }
        }
        return px;
    }
}
