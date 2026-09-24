package com.github.xiaozhaoz1.dshpet.core;

/**
 * RGBA 帧序列的几何计算 —— <b>纯 Java，零 MC 类型</b>（可完整 JVM 单测）。
 *
 * <p>用途：上游素材画布含大量透明边（220×124 画布，角色仅占约 72×92），
 * 显示缩放必须按<b>内容盒</b>而非画布推导，否则角色会显示得过大
 * （用户实机反馈"太大了"即此因，见 docs/PLAN.md §3.1 相关记录）。</p>
 */
public final class FrameGeometry {

    /** 内容盒（像素，含边界；宽高 ≥1）。 */
    public record Bounds(int minX, int minY, int maxX, int maxY) {

        public int width() {
            return maxX - minX + 1;
        }

        public int height() {
            return maxY - minY + 1;
        }
    }

    /** alpha 阈值：低于/等于它视为透明（与上游裁剪语义一致）。 */
    public static final int ALPHA_THRESHOLD = 8;

    private FrameGeometry() {
    }

    /**
     * 计算所有帧的**非透明像素并集边界盒**。
     *
     * @param width    画布宽
     * @param height   画布高
     * @param frames   帧序列，每帧 = RGBA8 字节（长度须为 width*height*4）
     * @return 内容盒；**全透明或无帧时返回 {@code null}**（调用方需自行兜底）
     */
    public static Bounds unionBounds(int width, int height, Iterable<byte[]> frames) {
        if (width <= 0 || height <= 0 || frames == null) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        boolean any = false;
        for (byte[] px : frames) {
            if (px == null || px.length < width * height * 4) {
                continue; // 跳过坏帧: 不因单帧异常导致整体失败
            }
            int i = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int a = px[i + 3] & 0xFF;
                    i += 4;
                    if (a > ALPHA_THRESHOLD) {
                        any = true;
                        if (x < minX) {
                            minX = x;
                        }
                        if (y < minY) {
                            minY = y;
                        }
                        if (x > maxX) {
                            maxX = x;
                        }
                        if (y > maxY) {
                            maxY = y;
                        }
                    }
                }
            }
        }
        return any ? new Bounds(minX, minY, maxX, maxY) : null;
    }

    /**
     * 由内容高度推导显示缩放：{@code scale = 目标高度 / 内容高度}，钳制到 [min, max]。
     *
     * @param contentHeight 内容盒高度（≤0 时返回 1.0 兜底）
     * @param targetHeight  期望显示高度（px）
     */
    public static float scaleFor(int contentHeight, float targetHeight, float min, float max) {
        if (contentHeight <= 0 || targetHeight <= 0 || max <= 0 || min <= 0 || min > max) {
            return 1f;
        }
        float s = targetHeight / contentHeight;
        if (s < min) {
            return min;
        }
        return Math.min(s, max);
    }

    /**
     * 显示缩放的**统一决策入口**（唯一事实源，避免多处各自钳制导致行为打架）。
     *
     * <p>血泪教训（2026-09-22 实机）：曾在渲染层额外加"角色高度不超过屏高 22%"的比例钳制，
     * 结果在 GUI Scale 放大时 {@code guiHeight} 变小（如 540），22% ≈ 119px 看似合理，
     * 但当内容盒较大/目标被挤压时会得到 0.61× ⇒ 角色只画 44×56 px，**用户实测"小得离谱"**。
     * ⇒ 结论：**角色显示尺寸用绝对像素定，不随 GUI Scale / 窗口比例缩放**，
     * 仅保留绝对上下界（防素材异常）。</p>
     *
     * @param contentHeight 内容盒高度（px）
     * @param targetHeight  期望显示高度（px，绝对像素）
     * @param minPx         允许的最小显示高度（px）
     * @param maxPx         允许的最大显示高度（px）
     * @return 缩放系数（非法入参返回 1.0）
     */
    public static float displayScale(int contentHeight, float targetHeight, float minPx, float maxPx) {
        if (contentHeight <= 0 || targetHeight <= 0 || minPx <= 0 || maxPx <= 0 || minPx > maxPx) {
            return 1f;
        }
        float target = targetHeight;
        if (target < minPx) {
            target = minPx;
        } else if (target > maxPx) {
            target = maxPx;
        }
        return target / contentHeight;
    }

    /**
     * **画布缩放的唯一决策入口**：两条约束取更小者。
     *
     * <pre>
     * 约束①（宽）: 期望角色宽 = 屏宽 × widthRatio（夹绝对边界） ⇒ scale = 期望宽 / 内容宽
     * 约束②（高）: 角色高 ≤ 屏高 × heightRatio              ⇒ scale = 上限高 / 内容高
     * 结果 = min(①, ②)
     * </pre>
     *
     * <p><b>为什么要双约束</b>（2026-09-23 实机四次反复的真因）：只做宽约束 ⇒ 近正方形 GUI 下角色
     * 过高（实测 480×255 时高 147px = 占屏高 58%，观感"巨大"）；只做高约束 ⇒ 又过小。
     * 两条同时成立才稳。</p>
     *
     * @param screenW    屏宽（GUI 逻辑像素）
     * @param screenH    屏高
     * @param contentW   内容盒宽（角色本体像素宽）
     * @param contentH   内容盒高
     * @param widthRatio 宽比例（如 0.15）
     * @param heightRatio 高比例上限（如 0.25）
     * @param minWidthPx 角色宽绝对下限
     * @param maxWidthPx 角色宽绝对上限
     * @return 画布缩放系数；非法入参返回 1.0
     */
    public static float scaleForViewport(int screenW, int screenH,
                                        int contentW, int contentH,
                                        float widthRatio, float heightRatio,
                                        float minWidthPx, float maxWidthPx) {
        if (screenW <= 0 || screenH <= 0 || contentW <= 0 || contentH <= 0
                || widthRatio <= 0 || heightRatio <= 0 || minWidthPx <= 0 || maxWidthPx <= 0
                || minWidthPx > maxWidthPx) {
            return 1f;
        }
        float byWidth = targetWidthFor(screenW, widthRatio, minWidthPx, maxWidthPx) / contentW;
        float byHeight = (screenH * heightRatio) / contentH;
        return Math.min(byWidth, byHeight);
    }

    /**
     * 目标角色高度的**唯一决策入口**：按屏高比例取，再夹到绝对上下界。
     *
     * <p>为什么不能只用固定像素（2026-09-23 实机取证）：用户 GUI 逻辑分辨率仅 <b>480×255</b>
     * （GUI Scale≈3.3），固定 110px 会占屏高 **43%**，观感上就是"太大"；
     * 而另一台 1080p（guiHeight≈540+）则显得过小。⇒ 必须<b>跟屏高成比例</b>，
     * 但又要绝对边界兜底（极小屏不至于看不见、极大屏不至于夸张）。</p>
     *
     * @param screenHeight GUI 逻辑高度（{@code graphics.guiHeight()}）
     * @param ratio        期望占屏高比例（如 0.20）
     * @param minPx        绝对最小高度
     * @param maxPx        绝对最大高度
     * @return 目标高度（px）；非法入参返回 minPx
     */
    public static float targetHeightFor(float screenHeight, float ratio, float minPx, float maxPx) {
        if (screenHeight <= 0 || ratio <= 0 || minPx <= 0 || maxPx <= 0 || minPx > maxPx) {
            return 1f; // 非法配置(含 min>max): 退回 1px 安全值, 不抛异常(渲染热路径每帧调用)
        }
        float want = screenHeight * ratio;
        if (want < minPx) {
            return minPx;
        }
        return Math.min(want, maxPx);
    }

    /**
     * 按**屏幕宽度比例**决定角色宽度（唯一入口）—— 对齐上游 dsh-pet 的尺寸语义。
     *
     * <p>依据（上游源码实证，见 docs/PLAN.md 与 dsh-pet `shared/constants.ts`）：
     * 上游基准 {@code PET_REF_WIDTH = 462}，默认 {@code size = 462}，设计窗口宽 1920
     * ⇒ <b>宠物宽 ≈ 屏宽 24%</b>。用屏高定尺寸是错的（同一像素值在不同 GUI Scale 下观感差数倍，
     * 这正是本会话反复"太大/太小"的根因）。</p>
     *
     * @param screenWidth GUI 逻辑宽度（{@code graphics.guiWidth()}）
     * @param ratio       期望占屏宽比例（默认 0.24，对齐上游）
     * @param minPx       绝对最小宽度（px）
     * @param maxPx       绝对最大宽度（px）
     * @return 目标角色宽度（px）；非法入参返回 1px 安全值
     */
    public static float targetWidthFor(float screenWidth, float ratio, float minPx, float maxPx) {
        if (screenWidth <= 0 || ratio <= 0 || minPx <= 0 || maxPx <= 0 || minPx > maxPx) {
            return 1f;
        }
        float want = screenWidth * ratio;
        if (want < minPx) {
            return minPx;
        }
        return Math.min(want, maxPx);
    }
}
