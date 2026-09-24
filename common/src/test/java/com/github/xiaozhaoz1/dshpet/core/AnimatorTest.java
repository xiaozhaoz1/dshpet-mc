package com.github.xiaozhaoz1.dshpet.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Animator} 单测 —— 纯 JVM，<b>不碰任何 MC 类型</b>（纪律：单测不得引用 MC 类）。
 *
 * <p>覆盖：帧推进、循环、非循环结束、重启、帧率换算、非法参数。</p>
 */
class AnimatorTest {

    @Test
    @DisplayName("每帧 2 tick：tick 1 次不动，第 2 次进下一帧")
    void advancesAfterTicksPerFrame() {
        Animator a = new Animator(3, 2, true);
        assertEquals(0, a.frameIndex());
        assertEquals(Animator.Advance.PLAYING, a.tick());
        assertEquals(0, a.frameIndex(), "第 1 tick 应仍在第 0 帧");
        assertEquals(Animator.Advance.PLAYING, a.tick());
        assertEquals(1, a.frameIndex(), "第 2 tick 应进入第 1 帧");
    }

    @Test
    @DisplayName("循环：末帧后再 tick 回到第 0 帧并报 LOOPED")
    void loopsBackToZero() {
        Animator a = new Animator(2, 1, true);
        assertEquals(Animator.Advance.PLAYING, a.tick()); // 0 -> 1
        assertEquals(1, a.frameIndex());
        assertEquals(Animator.Advance.LOOPED, a.tick());
        assertEquals(0, a.frameIndex());
    }

    @Test
    @DisplayName("非循环：末帧再 tick 报 FINISHED 且停在末帧（不越界）")
    void finishesAndStaysOnLastFrame() {
        Animator a = new Animator(2, 1, false);
        assertEquals(Animator.Advance.PLAYING, a.tick());
        assertEquals(Animator.Advance.FINISHED, a.tick());
        assertEquals(1, a.frameIndex(), "FINISHED 后应停在末帧");
        // 再次 tick 仍报 FINISHED（幂等，不会越界）
        assertEquals(Animator.Advance.FINISHED, a.tick());
        assertEquals(1, a.frameIndex());
    }

    @Test
    @DisplayName("单帧动画：循环模式直接报 LOOPED，非循环报 FINISHED")
    void singleFrameClip() {
        Animator loop = new Animator(1, 1, true);
        assertEquals(Animator.Advance.LOOPED, loop.tick());
        assertEquals(0, loop.frameIndex());

        Animator once = new Animator(1, 1, false);
        assertEquals(Animator.Advance.FINISHED, once.tick());
        assertEquals(0, once.frameIndex());
    }

    @Test
    @DisplayName("restart 归零帧索引与帧内计数")
    void restartResetsState() {
        Animator a = new Animator(3, 2, true);
        a.tick();
        a.tick(); // 到第 1 帧
        assertEquals(1, a.frameIndex());
        a.restart();
        assertEquals(0, a.frameIndex());
        assertEquals(Animator.Advance.PLAYING, a.tick(), "restart 后帧内计数也应归零");
        assertEquals(0, a.frameIndex());
    }

    @Test
    @DisplayName("帧率换算：12fps 素材 → 2 tick/帧（20 TPS 离散化）")
    void ticksPerFrameForFps() {
        assertEquals(2, Animator.ticksPerFrameFor(12.0), "12fps ≈ 1.67 tick ⇒ 取 2");
        assertEquals(1, Animator.ticksPerFrameFor(20.0));
        assertEquals(1, Animator.ticksPerFrameFor(24.0), "快于 20fps 也只能 1 tick/帧（下限保护）");
        assertEquals(4, Animator.ticksPerFrameFor(5.0));
        assertEquals(20, Animator.ticksPerFrameFor(1.0));
    }

    @Test
    @DisplayName("非法参数快速失败：frameCount / ticksPerFrame / fps 必须为正")
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new Animator(0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Animator(-1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new Animator(1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Animator(1, -2, true));
        assertThrows(IllegalArgumentException.class, () -> Animator.ticksPerFrameFor(0));
        assertThrows(IllegalArgumentException.class, () -> Animator.ticksPerFrameFor(-1.0));
    }

    @Test
    @DisplayName("整条走完一循环的 tick 总数 = 帧数 × 每帧 tick 数")
    void fullCycleTickCount() {
        int frames = 5;
        int tpf = 3;
        Animator a = new Animator(frames, tpf, true);
        int ticks = 0;
        int loops = 0;
        while (loops == 0) {
            ticks++;
            if (a.tick() == Animator.Advance.LOOPED) {
                loops++;
            }
            assertTrue(ticks <= frames * tpf, "循环应在 " + (frames * tpf) + " tick 内完成");
        }
        assertEquals(frames * tpf, ticks);
    }
}
