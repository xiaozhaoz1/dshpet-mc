package com.github.xiaozhaoz1.dshpet.screen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssetScreenLayout} 单测 —— 纯数学，零 MC 依赖。
 *
 * <p>依据 MC 原版资源包界面的布局公式（{@code height - 66}、页脚 spacing 8 等），
 * 断言坐标/尺寸/状态可见性规则。**这些是"我看不见画面"时唯一能自证的部分**（用户裁定：
 * 参数我能看见，就该验证参数）。</p>
 */
class AssetScreenLayoutTest {

    @Test
    @DisplayName("列表高度 = 屏高 - 66（原版公式），极端小窗保底 ≥1")
    void listHeightFollowsVanilla() {
        assertEquals(480 - 66, AssetScreenLayout.listHeight(480));
        assertEquals(240 - 66, AssetScreenLayout.listHeight(240));
        assertEquals(1, AssetScreenLayout.listHeight(66), "屏高等于预留高度 ⇒ 保底 1，不为 0/负");
        assertEquals(1, AssetScreenLayout.listHeight(10));
    }

    @Test
    @DisplayName("两列宽度：各占一半减间隙，窄窗保底 100（原版单列 200）")
    void columnWidthIsHalfMinusGap() {
        // 480 宽（用户环境，GUI 逻辑宽）：两列各 (480-8)/2 = 236
        assertEquals(236, AssetScreenLayout.columnWidth(480));
        // 窄窗 150：half=71 < 100 ⇒ 保底 100（宁可溢出也不挤成不可读）
        assertEquals(100, AssetScreenLayout.columnWidth(150));
    }

    @Test
    @DisplayName("两列整体居中：左列 X + 右列 X 对称")
    void columnsAreCentred() {
        int w = 480;
        int left = AssetScreenLayout.leftColumnX(w);
        int right = AssetScreenLayout.rightColumnX(w);
        int colW = AssetScreenLayout.columnWidth(w);
        assertEquals(colW + AssetScreenLayout.COLUMN_GAP, right - left, "右列 = 左列 + 列宽 + 间隙");
        assertEquals(w - (right + colW), left, "右侧剩余应等于左侧起点（居中）");
    }

    @Test
    @DisplayName("行内按钮右侧对齐：最右按钮距行右 4px，往左依次错开")
    void buttonsRightAligned() {
        int rowRight = 400;
        int b = 60;
        assertEquals(rowRight - 4 - b, AssetScreenLayout.buttonX(rowRight, b, 0));
        assertEquals(rowRight - 4 - b - (b + 4), AssetScreenLayout.buttonX(rowRight, b, 1));
        assertEquals(rowRight - 4 - b - 2 * (b + 4), AssetScreenLayout.buttonX(rowRight, b, 2));
    }

    @Test
    @DisplayName("可读体积：0/负数/边界/各级单位")
    void readableSizeIsSane() {
        assertEquals("0B", AssetScreenLayout.readableSize(0));
        assertEquals("0B", AssetScreenLayout.readableSize(-5), "负数不崩，按 0 处理");
        assertEquals("512B", AssetScreenLayout.readableSize(512));
        assertEquals("1.0kB", AssetScreenLayout.readableSize(1024));
        assertEquals("1.0MB", AssetScreenLayout.readableSize(1024 * 1024));
        assertEquals("1.0GB", AssetScreenLayout.readableSize(1024L * 1024 * 1024));
        // 实测素材：622863B ⇒ 608.3kB
        assertEquals("608.3kB", AssetScreenLayout.readableSize(622863));
        // B 级不带小数
        assertEquals("1023B", AssetScreenLayout.readableSize(1023));
    }

    @Test
    @DisplayName("按钮可见性规则：下载/更新/启用/删除")
    void buttonVisibilityRules() {
        // 未安装：可下载，不可启用/删除
        assertTrue(AssetScreenLayout.showDownloadButton(false, false));
        assertFalse(AssetScreenLayout.showEnableButton(false, false));
        assertFalse(AssetScreenLayout.showRemoveButton(false));

        // 已安装且启用：不可再启用；可删除；版本落后才可更新
        assertFalse(AssetScreenLayout.showEnableButton(true, true));
        assertTrue(AssetScreenLayout.showRemoveButton(true));
        assertFalse(AssetScreenLayout.showDownloadButton(true, false));
        assertTrue(AssetScreenLayout.showDownloadButton(true, true), "版本落后 ⇒ 显示更新");

        // 已安装未启用：显示启用
        assertTrue(AssetScreenLayout.showEnableButton(true, false));
    }

    @Test
    @DisplayName("行高与页脚常量与原版口径一致")
    void constantsMatchVanilla() {
        assertEquals(66, AssetScreenLayout.VERTICAL_CHROME, "原版 height - 66");
        assertEquals(8, AssetScreenLayout.FOOTER_SPACING, "原版 horizontal().spacing(8)");
        assertEquals(5, AssetScreenLayout.HEADER_SPACING, "原版 vertical().spacing(5)");
        assertEquals(20, AssetScreenLayout.BUTTON_HEIGHT, "MC Button 默认高 20");
    }
}
