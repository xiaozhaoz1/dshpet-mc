package com.github.xiaozhaoz1.dshpet.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigScreenLayout} 单测 —— 纯逻辑（配置屏坐标按铁律「先给坐标表」定死，此处可断言）。
 *
 * <p>重点：① 与 {@code PLAN.md §4.3} 的**逐值对照**（写码时禁止即兴）② 极小屏适配
 * （用户实测 GUI 仅 255 高 &lt; 面板 292）③ 自检不越界/不重叠。</p>
 */
class ConfigScreenLayoutTest {

    @Test
    @DisplayName("§4.3 对照: 面板 320 宽、292 高、x=(screenW-320)/2")
    void matchesPlanSection43() {
        assertEquals(320, ConfigScreenLayout.PANEL_W);
        assertEquals(292, ConfigScreenLayout.PANEL_H);
        // 屏 480 ⇒ 面板 x = (480-320)/2 = 80
        assertEquals(80, ConfigScreenLayout.panelX(480));
        // 屏高 320 ⇒ 面板保持基准 292，居中 y = (320-292)/2 = 14
        assertEquals(292, ConfigScreenLayout.panelH(320));
        assertEquals(14, ConfigScreenLayout.panelY(320));
    }

    @Test
    @DisplayName("§4.3 对照: 底栏 5 按钮 x = 8/68/128/188/248，宽 52，高 18，右端 300")
    void footerMatchesPlan() {
        int[] expectedX = {8, 68, 128, 188, 248};
        for (int i = 0; i < expectedX.length; i++) {
            assertEquals(expectedX[i], ConfigScreenLayout.footerBtnX(i), "第 " + i + " 个按钮 x");
        }
        assertEquals(52, ConfigScreenLayout.FOOTER_BTN_W);
        assertEquals(18, ConfigScreenLayout.FOOTER_BTN_H);
        assertEquals(8, ConfigScreenLayout.FOOTER_GAP);
        assertEquals(300, ConfigScreenLayout.footerRightEdge(), "右端应 = 300（面板 320 右侧留 20）");
        assertTrue(ConfigScreenLayout.footerRightEdge() <= ConfigScreenLayout.PANEL_W - 20,
                "底栏不得贴到面板右边缘");
    }

    @Test
    @DisplayName("§4.3 对照: 面板 292 高时底栏 y = 262（与坐标表逐值一致）")
    void footerYAtBaseHeight() {
        assertEquals(262, ConfigScreenLayout.footerY(292), "§4.3 底栏 y=262");
    }

    @Test
    @DisplayName("§4.3 对照: 内容行自 y=26 起、步进 22")
    void rowGeometryMatchesPlan() {
        assertEquals(26, ConfigScreenLayout.rowY(0));
        assertEquals(48, ConfigScreenLayout.rowY(1));
        assertEquals(70, ConfigScreenLayout.rowY(2));
        assertEquals(92, ConfigScreenLayout.rowY(3));
    }

    @Test
    @DisplayName("极小屏适配: 用户实测 GUI 480x255（高 255 < 面板 292）⇒ 面板压缩且不越界")
    void adaptsToSmallScreen() {
        int h = 255;
        int panelH = ConfigScreenLayout.panelH(h);
        assertTrue(panelH <= h, "面板高不得超出屏幕");
        assertEquals(247, panelH, "255 - 8(留白) = 247");

        int panelY = ConfigScreenLayout.panelY(h);
        assertEquals(4, panelY, "(255-247)/2 = 4");
        assertTrue(panelY + panelH <= h, "面板底不得越出屏幕");

        // 底栏随面板底吸附
        int footerY = ConfigScreenLayout.footerY(panelH);
        assertEquals(217, footerY);
        assertTrue(footerY + ConfigScreenLayout.FOOTER_BTN_H <= panelH, "底栏不得越出面板");
    }

    @Test
    @DisplayName("极小屏适配: 内容行不得与底栏重叠（该屏可容纳的行数 ≥ 我们的 5 行）")
    void contentDoesNotOverlapFooter() {
        int screenH = 255;
        int maxRows = ConfigScreenLayout.maxRows(screenH);
        assertTrue(maxRows >= 5, () -> "255 高应至少容纳 5 行，实际 " + maxRows);

        int lastRowY = ConfigScreenLayout.rowY(4);
        int footerY = ConfigScreenLayout.footerY(ConfigScreenLayout.panelH(screenH));
        assertTrue(lastRowY + ConfigScreenLayout.CONTROL_H <= footerY,
                () -> "最后一行底(" + (lastRowY + ConfigScreenLayout.CONTROL_H) + ") 不得压到底栏(" + footerY + ")");
    }

    @Test
    @DisplayName("极端屏: 高度很小/很大都不产生负坐标或越界")
    void extremeScreens() {
        for (int h : new int[] {80, 120, 180, 255, 292, 480, 1080}) {
            int ph = ConfigScreenLayout.panelH(h);
            int py = ConfigScreenLayout.panelY(h);
            assertTrue(ph > 0, () -> "面板高应 > 0 (h=" + h + ")");
            assertTrue(py >= 0, () -> "面板 y 不得为负 (h=" + h + ")");
            assertTrue(py + ph <= Math.max(h, ph), () -> "面板不得越界 (h=" + h + ")");
            int fy = ConfigScreenLayout.footerY(ph);
            assertTrue(fy >= 0 && fy + ConfigScreenLayout.FOOTER_BTN_H <= ph,
                    () -> "底栏应在面板内 (h=" + h + ")");
            assertTrue(ConfigScreenLayout.maxRows(h) >= 0, () -> "行数不得为负 (h=" + h + ")");
        }
    }

    @Test
    @DisplayName("窄屏: 面板宽超过屏宽时 x 取 0（不产生负坐标）")
    void narrowScreenClampsToZero() {
        assertEquals(0, ConfigScreenLayout.panelX(200), "屏比面板窄 ⇒ x=0");
        assertEquals(0, ConfigScreenLayout.panelX(320));
        assertEquals(8, ConfigScreenLayout.panelX(336));
    }
}
