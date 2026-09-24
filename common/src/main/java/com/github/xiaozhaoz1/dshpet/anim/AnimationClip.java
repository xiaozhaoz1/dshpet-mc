package com.github.xiaozhaoz1.dshpet.anim;

import java.util.List;

/**
 * 一个动画的元数据 + 已解码帧（像素层，不涉及 MC 纹理）。
 *
 * <p>设计要点（docs/PLAN.md §3.1 决策 1「惰性解帧」）：扫描阶段只拿
 * {@link #frameCount}/{@link #width}/{@link #height}（廉价），首次播放才解码整条帧数据。</p>
 *
 * @param name      动画名（= GIF 文件名去扩展名；ASCII 名，素材已确认全 ASCII）
 * @param source    来源（内置 / 用户目录），用于界面显示与优先级说明
 * @param width     画布宽（px）
 * @param height    画布高（px）
 * @param fps       素材帧率（由 GIF 帧延时推导；无法推导时用 {@link #DEFAULT_FPS}）
 * @param frames    已解码帧：每帧 = RGBA8 像素数组（长度 = width*height*4，行主序，颜色通道顺序 R,G,B,A）
 */
public record AnimationClip(
        String name,
        Source source,
        int width,
        int height,
        double fps,
        List<byte[]> frames
) {

    /** 素材帧率缺失时的兜底（实测上游 GIF 延时 8/9cs 交替 ⇒ 12 fps）。 */
    public static final double DEFAULT_FPS = 12.0;

    public enum Source {
        /** jar 内置（只读基线）。 */
        BUILTIN,
        /** 用户目录（优先级更高，可覆盖同名内置）。 */
        USER
    }

    public AnimationClip {
        // record 紧凑构造器做防御校验: 坏数据应尽早暴露, 而不是等到渲染时才崩
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad size " + width + "x" + height);
        }
        if (fps <= 0) {
            throw new IllegalArgumentException("fps must be > 0, got " + fps);
        }
        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("frames must not be empty");
        }
        frames = List.copyOf(frames); // 不可变快照，防调用方误改
    }

    public int frameCount() {
        return frames.size();
    }

    /** 取某帧的 RGBA 像素；越界时钳制到合法范围（渲染热路径不做异常抛出，避免崩客户端）。 */
    public byte[] frame(int index) {
        int i = index;
        if (i < 0) {
            i = 0;
        } else if (i >= frames.size()) {
            i = frames.size() - 1;
        }
        return frames.get(i);
    }

    /** 每帧字节数（= 单帧 RGBA 内存，用于内存预算核算）。 */
    public int bytesPerFrame() {
        return width * height * 4;
    }
}
