package com.github.xiaozhaoz1.dshpet.core;

/**
 * 动画时间轴 —— <b>纯 Java，零 MC 类型依赖</b>，可完整 JVM 单测。
 *
 * <p>职责边界（见 docs/PLAN.md §3 L2）：只推进"当前是第几帧 / 该不该换动画"，
 * <b>不持有纹理、不碰渲染</b>。纹理与显示由 L3 RenderBackend 负责。</p>
 *
 * <p>上游对应：dsh-pet 的动画链由 video 的 {@code ended} 事件 + 权重选择驱动；
 * MC 里没有 media 事件，改为<b>时间轴按 tick 推进</b>（MC 恒定 20 TPS）。</p>
 */
public final class Animator {

    /** 一次播放到达末尾（且不循环）时的决策结果 —— 由调用方（动画链）决定下一个动画。 */
    public enum Advance {
        /** 仍在当前动画内。 */
        PLAYING,
        /** 本帧刚回到第 0 帧（循环模式）。 */
        LOOPED,
        /** 播放到尾帧（非循环）⇒ 调用方应选下一个动画。 */
        FINISHED
    }

    private final int frameCount;
    /** 每帧持续 tick 数（≥1）。12fps 素材在 20 TPS 下的典型值 = 2（≈10fps 观感）或 1（≈20fps 上限）。 */
    private final int ticksPerFrame;
    private final boolean loop;

    private int frameIndex;
    private int tickInFrame;

    public Animator(int frameCount, int ticksPerFrame, boolean loop) {
        if (frameCount <= 0) {
            throw new IllegalArgumentException("frameCount must be > 0, got " + frameCount);
        }
        if (ticksPerFrame <= 0) {
            throw new IllegalArgumentException("ticksPerFrame must be > 0, got " + ticksPerFrame);
        }
        this.frameCount = frameCount;
        this.ticksPerFrame = ticksPerFrame;
        this.loop = loop;
    }

    /** 当前帧索引（0-based）。 */
    public int frameIndex() {
        return frameIndex;
    }

    public int frameCount() {
        return frameCount;
    }

    public boolean loop() {
        return loop;
    }

    /**
     * 推进一个 tick。
     *
     * @return {@link Advance#PLAYING} 未到换帧；{@link Advance#LOOPED} 循环回第 0 帧；
     *         {@link Advance#FINISHED} 非循环播放到末帧（帧索引停在最后一帧）
     */
    public Advance tick() {
        if (++tickInFrame < ticksPerFrame) {
            return Advance.PLAYING;
        }
        tickInFrame = 0;
        if (frameIndex + 1 < frameCount) {
            frameIndex++;
            return Advance.PLAYING;
        }
        // 已在末帧
        if (loop) {
            frameIndex = 0;
            return Advance.LOOPED;
        }
        return Advance.FINISHED;
    }

    /** 重播：帧索引与帧内计数归零（切动画 / 重新触发同一动画时用）。 */
    public void restart() {
        frameIndex = 0;
        tickInFrame = 0;
    }

    /**
     * 素材帧率 → 每帧 tick 数（20 TPS 下的离散化）。
     *
     * <p>例：12 fps 素材 ⇒ 20/12 ≈ 1.67 ⇒ 无法整除，取 {@code max(1, round(20/fps))} = 2
     * （观感 ≈10fps，轻微变慢但平稳；取 1 会变成 20fps 明显加速）。</p>
     */
    public static int ticksPerFrameFor(double fps) {
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be > 0, got " + fps);
        }
        return Math.max(1, (int) Math.round(20.0 / fps));
    }
}
