package com.github.xiaozhaoz1.dshpet.screen;

/**
 * 配置屏坐标（**纯逻辑，零 MC 依赖 ⇒ 可单测**）。
 *
 * <p><b>权威来源</b>：{@code docs/PLAN.md} §4.3 坐标表（用户「GUI 必给坐标」纪律的产物，
 * 写作时禁止即兴）。本类把它变成可断言的函数，并补两类现实适配：</p>
 *
 * <ol>
 *   <li><b>面板居中</b>：{@code (screenW - 320) / 2}，与 §4.3 一致</li>
 *   <li><b>高度自适应</b>（§4.3 未覆盖的现实约束）：§4.3 假定 292 高的面板，但逻辑高度可能更矮
 *       （实测用户环境 guiScale=4 时仅 255）⇒ 面板高取 {@code min(292, screenH - 顶底留白)}，
 *       底栏吸附到面板底（面板=292 时恰好等于 §4.3 的 y=262）。</li>
 * </ol>
 *
 * <p>底栏 5 按钮几何直接取自 §4.3：{@code x = 8/68/128/188/248，宽 52，高 18}
 * （5×52 + 4×8 = 292，右端 300 ⇒ 面板 320 右侧留 20）。</p>
 */
public final class ConfigScreenLayout {

    /** 面板基准宽（§4.3）。 */
    public static final int PANEL_W = 320;

    /** 面板基准高（§4.3）。 */
    public static final int PANEL_H = 292;

    /** 面板与屏幕边缘的最小留白（高度自适应用）。 */
    public static final int SCREEN_MARGIN = 8;

    /** 内容行：起始 y 与行距（与 §4.3 的 26 起、22 步进一致的密度）。 */
    public static final int ROW_FIRST_Y = 26;
    public static final int ROW_PITCH = 22;

    /** 控件标准高（§4.3 用 18）。 */
    public static final int CONTROL_H = 18;

    /** 底栏：按钮宽/高/间距/左起 x（§4.3）。 */
    public static final int FOOTER_BTN_W = 52;
    public static final int FOOTER_BTN_H = 18;
    public static final int FOOTER_GAP = 8;
    public static final int FOOTER_FIRST_X = 8;
    public static final int FOOTER_COUNT = 5;

    /** 底栏相对面板底部的偏移（§4.3: 面板 292 时底栏 y=262 ⇒ 292-262=30）。 */
    public static final int FOOTER_FROM_BOTTOM = 30;

    private ConfigScreenLayout() {
    }

    /** 面板高度：基准 292，但不超过屏幕可用高度。 */
    public static int panelH(int screenH) {
        return Math.max(CONTROL_H + FOOTER_FROM_BOTTOM * 2, Math.min(PANEL_H, screenH - SCREEN_MARGIN));
    }

    /** 面板左上角 x（居中）。 */
    public static int panelX(int screenW) {
        return Math.max(0, (screenW - PANEL_W) / 2);
    }

    /** 面板左上角 y（居中；屏幕比面板高时居中，否则贴留白）。 */
    public static int panelY(int screenH) {
        int h = panelH(screenH);
        return Math.max(0, (screenH - h) / 2);
    }

    /** 第 {@code row} 行（0 起）在面板内的 y。 */
    public static int rowY(int row) {
        return ROW_FIRST_Y + row * ROW_PITCH;
    }

    /** 底栏在面板内的 y（面板 292 ⇒ 262，与 §4.3 一致）。 */
    public static int footerY(int panelHeight) {
        return panelHeight - FOOTER_FROM_BOTTOM;
    }

    /** 底栏第 {@code index} 个按钮在面板内的 x（0 起；§4.3 = 8/68/128/188/248）。 */
    public static int footerBtnX(int index) {
        return FOOTER_FIRST_X + index * (FOOTER_BTN_W + FOOTER_GAP);
    }

    /** 底栏按钮右端 x（用于自检：应 ≤ 面板宽 - 20）。 */
    public static int footerRightEdge() {
        return footerBtnX(FOOTER_COUNT - 1) + FOOTER_BTN_W;
    }

    /** 内容区可用的最后一行 y（底栏之上留 8px）。 */
    public static int contentBottomY(int screenH) {
        return footerY(panelH(screenH)) - 8;
    }

    /** 给定屏幕高度，内容区能放下多少行（用于极小屏决定是否隐藏次要行）。 */
    public static int maxRows(int screenH) {
        int usable = contentBottomY(screenH) - ROW_FIRST_Y;
        return usable < 0 ? 0 : usable / ROW_PITCH + 1;
    }
}
