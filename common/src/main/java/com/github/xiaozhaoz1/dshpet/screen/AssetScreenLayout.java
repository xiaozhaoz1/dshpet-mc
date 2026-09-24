package com.github.xiaozhaoz1.dshpet.screen;

/**
 * 素材界面布局常量与纯数学（**零 MC 依赖 ⇒ 可单测**）。
 *
 * <p>取值依据：**MC 原版资源包界面**（`PackSelectionScreen`，1.21.1 源码实证）——</p>
 * <pre>
 * LIST_WIDTH = 200          （原版列表宽）
 * 列表高     = height - 66  （原版：给 HeaderAndFooterLayout 的页眉/页脚留位）
 * 页眉       = LinearLayout.vertical().spacing(5) → 标题 + 副标题（居中）
 * 页脚       = LinearLayout.horizontal().spacing(8) → 按钮行
 * 行锚点     = AbstractSelectionList.getRowWidth()/getRowLeft()/getRowTop()/getRowBottom()
 * </pre>
 *
 * <p>本项目按用户裁定**全屏列表**（同原版资源包）：左「已安装」+ 右「可用」两列（原版亦为两列）。</p>
 */
public final class AssetScreenLayout {

    /** 与原版一致的页眉/页脚预留高度（原版 {@code height - 66}）。 */
    public static final int VERTICAL_CHROME = 66;

    /** 行高（原版资源包条目为两行文字的高度）。 */
    public static final int ROW_HEIGHT = 36;

    /** 页脚按钮标准尺寸（MC 原版 Button.builder 默认 20 高；宽度按文字自适应，这里给最小宽）。 */
    public static final int BUTTON_HEIGHT = 20;
    public static final int BUTTON_MIN_WIDTH = 74;

    /** 页脚按钮间距（原版 {@code LinearLayout.horizontal().spacing(8)}）。 */
    public static final int FOOTER_SPACING = 8;

    /** 页眉两行间距（原版 {@code vertical().spacing(5)}）。 */
    public static final int HEADER_SPACING = 5;

    /** 两列列表之间的间隙。 */
    public static final int COLUMN_GAP = 8;

    private AssetScreenLayout() {
    }

    /** 列表高度（与原版公式一致，并保底 ≥1 以免极端窗口算出 0/负）。 */
    public static int listHeight(int screenHeight) {
        return Math.max(1, screenHeight - VERTICAL_CHROME);
    }

    /** 单列列表宽度：全屏两列时各占一半减间隙；窗口过窄时保底 100（原版单列即 200）。 */
    public static int columnWidth(int screenWidth) {
        int half = (screenWidth - COLUMN_GAP) / 2;
        return Math.max(100, half);
    }

    /** 左列（已安装）X 原点：居中布局 ⇒ 两列整体居中。 */
    public static int leftColumnX(int screenWidth) {
        int total = columnWidth(screenWidth) * 2 + COLUMN_GAP;
        return Math.max(0, (screenWidth - total) / 2);
    }

    /** 右列（可用）X 原点。 */
    public static int rightColumnX(int screenWidth) {
        return leftColumnX(screenWidth) + columnWidth(screenWidth) + COLUMN_GAP;
    }

    /** 某列列表的 Y 原点（页眉之下）。 */
    public static int listY() {
        // 原版：HeaderAndFooterLayout 的 header 高度约 33（标题 + 副标题 + spacing）
        return 33;
    }

    /**
     * 行的可见高度：`getRowBottom - getRowTop`（供测试与自绘对齐用）。
     *
     * @param rowTop    行顶（来自列表的 getRowTop）
     * @param rowBottom 行底（来自列表的 getRowBottom）
     */
    public static int visibleRowHeight(int rowTop, int rowBottom) {
        return Math.max(1, rowBottom - rowTop);
    }

    /**
     * 行内按钮的 X 坐标：**行内右侧对齐**（留 4px 内边距）。
     *
     * @param rowRight      行右边界（列表 getRowRight）
     * @param buttonWidth   按钮宽度
     * @param indexFromRight 从右数第几个按钮（0 = 最右）
     */
    public static int buttonX(int rowRight, int buttonWidth, int indexFromRight) {
        int x = rowRight - 4 - buttonWidth;
        return x - indexFromRight * (buttonWidth + 4);
    }

    /**
     * 字节数 → 人类可读（单位阶梯 B/kB/MB/GB/TB）。
     *
     * <p>规则：{@code ≤0 → "0B"}；B 级不带小数；kB 及以上**保留 1 位小数**
     * （实测素材 622863B ⇒ "608.3kB"，比取整更有信息量；界面宽度足够）。</p>
     */
    public static String readableSize(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        String[] units = {"B", "kB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        if (unit == 0) {
            return bytes + units[0];
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value) + units[unit];
    }

    /**
     * 状态 → 是否显示"启用"按钮（只有已安装且非当前启用时才显示）。
     *
     * @param installed        是否已安装
     * @param isActive         是否当前启用
     */
    public static boolean showEnableButton(boolean installed, boolean isActive) {
        return installed && !isActive;
    }

    /** 状态 → 是否显示"删除"按钮（已安装才可删）。 */
    public static boolean showRemoveButton(boolean installed) {
        return installed;
    }

    /** 状态 → 是否显示"下载/更新"按钮（未安装 ⇒ 下载；已装且版本不同 ⇒ 更新）。 */
    public static boolean showDownloadButton(boolean installed, boolean needsUpdate) {
        return !installed || needsUpdate;
    }
}
