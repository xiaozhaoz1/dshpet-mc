package com.github.xiaozhaoz1.dshpet.screen;

import com.github.xiaozhaoz1.dshpet.DshPetLog;
import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.config.SharedConfig;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetCatalog;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetDownloader;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetManifest;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetPack;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetStore;
import com.github.xiaozhaoz1.dshpet.download.HttpAssetFetch;
import com.github.xiaozhaoz1.dshpet.render.PetHud;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 素材下载界面 —— **照 MC 原版资源包界面（{@code PackSelectionScreen}）的结构**：
 * 全屏 · {@code HeaderAndFooterLayout} 排页眉/页脚 · 左右两个 {@link ObjectSelectionList}
 * （左「已安装」/ 右「可用」）· 行内按钮 · 页脚全局按钮。用户裁定：全屏设置界面正常。
 *
 * <p><b>两版 API 差异（javap 实证，必须条件编译）</b>：</p>
 * <table border="1">
 *   <tr><th>项</th><th>1.20.1</th><th>1.21.1</th></tr>
 *   <tr><td>ObjectSelectionList 构造</td><td>(mc,w,h,y,itemH,scrollbar)</td><td>(mc,w,h,y,itemH)</td></tr>
 *   <tr><td>LinearLayout</td><td>{@code new LinearLayout(0,0,Orientation.X)}</td><td>{@code vertical()/horizontal()}</td></tr>
 *   <tr><td>布局默认单元</td><td>{@code defaultChildLayoutSetting()}</td><td>{@code defaultCellSetting()}</td></tr>
 * </table>
 *
 * <p><b>线程纪律</b>：清单与下载都在 {@link CompletableFuture} 异步链上，结果用
 * {@link Minecraft#execute} 回主线程；渲染/输入线程绝不联网。</p>
 *
 * <p><b>合规</b>：行内显示作者/许可/体积，页脚显示署名（数据源 = {@code pack.json} 单一来源）。</p>
 */
public final class AssetDownloadScreen extends Screen {

    private static final Component SUBTITLE = Component.translatable("dshpet.screen.assets.subtitle");
    private static final Component BTN_DEFAULT = Component.translatable("dshpet.screen.assets.download_default");
    private static final Component BTN_FOLDER = Component.translatable("dshpet.screen.assets.open_folder");
    private static final Component COL_INSTALLED = Component.translatable("dshpet.screen.assets.col.installed");
    private static final Component COL_AVAILABLE = Component.translatable("dshpet.screen.assets.col.available");
    private static final Component BTN_DOWNLOAD = Component.translatable("dshpet.screen.assets.action.download");
    private static final Component BTN_UPDATE = Component.translatable("dshpet.screen.assets.action.update");
    private static final Component BTN_REMOVE = Component.translatable("dshpet.screen.assets.action.remove");
    private static final Component BTN_ENABLE = Component.translatable("dshpet.screen.assets.action.enable");
    private static final Component ENABLED_MARK = Component.translatable("dshpet.screen.assets.enabled_mark");

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
    private final Screen parent;

    private AssetManifest manifest = AssetManifest.empty();
    private boolean loading;
    private Component statusLine = Component.empty();

    private PackList installedList;
    private PackList availableList;

    public AssetDownloadScreen(Screen parent) {
        super(Component.translatable("dshpet.screen.assets.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        newHeader(); // 页眉内容在方法内构建（两版 API 差异集中在那里）
        installedList = addRenderableWidget(new PackList(COL_INSTALLED));
        availableList = addRenderableWidget(new PackList(COL_AVAILABLE));

        newFooter(); // 页脚同上

        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
        refreshAsync();
    }

    /**
     * 页眉布局（**两版 API 差异集中点 1**）：1.20.1 的 {@code LinearLayout} **没有 spacing**
     * （javap 实证）⇒ 间距用子元素 {@code padding} 表达；1.21.1 有 {@code spacing(int)}。
     */
    private LinearLayout newHeader() {
//? if 1.20.1 {
        LinearLayout header = new LinearLayout(0, 0, LinearLayout.Orientation.VERTICAL);
        header.addChild(new StringWidget(getTitle(), font),
                header.newChildLayoutSettings().alignHorizontallyCenter());
        header.addChild(new StringWidget(SUBTITLE, font),
                header.newChildLayoutSettings().alignHorizontallyCenter()
                        .paddingTop(AssetScreenLayout.HEADER_SPACING));
//?} else {
        LinearLayout header = LinearLayout.vertical().spacing(AssetScreenLayout.HEADER_SPACING);
        header.defaultCellSetting().alignHorizontallyCenter();
        header.addChild(new StringWidget(getTitle(), font));
        header.addChild(new StringWidget(SUBTITLE, font));
//?}
        return layout.addToHeader(header);
    }

    /**
     * 页脚布局（**两版 API 差异集中点 2**）：同页眉，1.20.1 用 padding 表达间距。
     */
    private LinearLayout newFooter() {
        LinearLayout footer;
//? if 1.20.1 {
        footer = new LinearLayout(0, 0, LinearLayout.Orientation.HORIZONTAL);
        footer.addChild(Button.builder(BTN_DEFAULT, b -> downloadDefault()).build());
        footer.addChild(Button.builder(BTN_FOLDER, b -> openFolder()).build(),
                footer.newChildLayoutSettings().paddingLeft(AssetScreenLayout.FOOTER_SPACING));
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build(),
                footer.newChildLayoutSettings().paddingLeft(AssetScreenLayout.FOOTER_SPACING));
//?} else {
        footer = LinearLayout.horizontal().spacing(AssetScreenLayout.FOOTER_SPACING);
        footer.addChild(Button.builder(BTN_DEFAULT, b -> downloadDefault()).build());
        footer.addChild(Button.builder(BTN_FOLDER, b -> openFolder()).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
//?}
        return layout.addToFooter(footer);
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        int colW = AssetScreenLayout.columnWidth(width);
        int h = AssetScreenLayout.listHeight(height);
        int y = AssetScreenLayout.listY();
        if (installedList != null) {
            installedList.place(colW, h, AssetScreenLayout.leftColumnX(width), y);
        }
        if (availableList != null) {
            availableList.place(colW, h, AssetScreenLayout.rightColumnX(width), y);
        }
    }

    // ── 数据 ──────────────────────────────────────────────────────────────

    /** 异步拉清单 → 主线程刷新两列。 */
    private void refreshAsync() {
        if (loading) {
            return;
        }
        loading = true;
        statusLine = Component.translatable("dshpet.screen.assets.loading");
        var cache = ConfigPaths.manifestCacheFile();
        CompletableFuture
                .supplyAsync(() -> AssetCatalog.load(new HttpAssetFetch(), cache, com.github.xiaozhaoz1.dshpet.DshPet.VERSION))
                .thenAccept(m -> Minecraft.getInstance().execute(() -> {
                    manifest = m;
                    loading = false;
                    statusLine = m.packs().isEmpty()
                            ? Component.translatable("dshpet.screen.assets.no_manifest")
                            : Component.empty();
                    rebuildLists();
                }))
                .exceptionally(t -> {
                    Minecraft.getInstance().execute(() -> {
                        loading = false;
                        statusLine = Component.translatable("dshpet.assets.no_manifest");
                        rebuildLists();
                    });
                    return null;
                });
    }

    private void rebuildLists() {
        if (installedList == null || availableList == null) {
            return;
        }
        installedList.clearAll();
        availableList.clearAll();
        String active = SharedConfig.activePack();
        List<String> installedIds = new ArrayList<>();
        for (AssetStore.InstalledPack p : AssetStore.installed()) {
            installedIds.add(p.id());
            boolean isActive = p.id().equals(active);
            installedList.append(new PackRow(p.pack(), true, isActive,
                    AssetStore.needsUpdate(manifest.byId(p.id()), p)));
        }
        for (AssetPack remote : manifest.packs()) {
            if (!installedIds.contains(remote.id())) {
                availableList.append(new PackRow(remote, false, false, false));
            }
        }
    }

    // ── 动作 ──────────────────────────────────────────────────────────────

    private void downloadDefault() {
        AssetPack pack = manifest.defaultPack();
        if (pack == null) {
            statusLine = Component.translatable("dshpet.assets.no_default");
            return;
        }
        download(pack);
    }

    private void download(AssetPack pack) {
        statusLine = Component.translatable("dshpet.assets.installing", pack.id());
        AssetDownloader.Progress progress = new AssetDownloader.Progress() {
            private int lastShown;

            @Override
            public void onFileDone(int done, int total, String file) {
                int pct = total == 0 ? 100 : done * 100 / total;
                if (pct - lastShown < 5 && done < total) {
                    return;
                }
                lastShown = pct;
                Minecraft.getInstance().execute(() -> statusLine = Component.translatable(
                        "dshpet.assets.progress", String.valueOf(done),
                        String.valueOf(total), String.valueOf(pct)));
            }
        };
        CompletableFuture
                .supplyAsync(() -> AssetDownloader.download(pack, AssetStore.packDir(pack.id()),
                        new HttpAssetFetch(), progress))
                .thenAccept(r -> Minecraft.getInstance().execute(() -> {
                    if (r.ok()) {
                        SharedConfig.setActivePack(pack.id());
                        PetHud.reset();
                        statusLine = Component.translatable("dshpet.assets.installed", pack.id(),
                                String.valueOf(r.assetCount()));
                    } else {
                        statusLine = Component.translatable("dshpet.assets.failed", pack.id(), r.message());
                    }
                    refreshAsync();
                }))
                .exceptionally(t -> {
                    Minecraft.getInstance().execute(() -> statusLine = Component.translatable(
                            "dshpet.assets.failed", pack.id(), t.toString()));
                    return null;
                });
    }

    private void enable(String packId) {
        SharedConfig.setActivePack(packId);
        PetHud.reset();
        statusLine = Component.translatable("dshpet.assets.enabled", packId);
        rebuildLists();
    }

    private void remove(String packId) {
        // 直接用布尔结果决定动作 —— 不比较翻译后的字符串（中文环境/键缺失时那种比较会失效）
        boolean removed = AssetStore.remove(packId);
        if (removed) {
            PetHud.reset();
            statusLine = Component.translatable("dshpet.assets.removed", packId);
        } else {
            statusLine = Component.translatable("dshpet.assets.not_installed", packId);
        }
        rebuildLists();
    }

    private void openFolder() {
        try {
            Util.getPlatform().openFile(ConfigPaths.assetsDir().toFile());
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.ASSET, "打开素材目录失败: {}", t.toString());
        }
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int y = height - AssetScreenLayout.VERTICAL_CHROME + 2;
        if (!statusLine.getString().isEmpty()) {
            graphics.drawCenteredString(font, statusLine, width / 2, y, 0xFFAA00);
        }
        graphics.drawCenteredString(font, attributionLine(), width / 2, y + 12, 0x808080);
    }

    /** 署名行（数据源：已安装包的 `pack.json`；无包时给上游默认口径）。 */
    private Component attributionLine() {
        List<AssetStore.InstalledPack> installed = AssetStore.installed();
        if (!installed.isEmpty()) {
            return Component.literal(installed.get(0).pack().attribution());
        }
        return Component.translatable("dshpet.screen.assets.attribution_default");
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    // ── 列表 ──────────────────────────────────────────────────────────────

    /** 一列包列表（照原版：继承 {@link ObjectSelectionList}，行高 {@link AssetScreenLayout#ROW_HEIGHT}）。 */
    private final class PackList extends ObjectSelectionList<PackRow> {

        private final Component columnTitle;

        /**
         * 构造（**两版 API 差异集中点 3**）：1.20.1 的 {@code ObjectSelectionList} 构造器多一个
         * {@code scrollbarWidth} 参数（javap 实证）⇒ 整条 super 调用按版本条件编译。
         */
        PackList(Component columnTitle) {
//? if 1.20.1 {
            super(AssetDownloadScreen.this.minecraft, AssetDownloadScreen.this.width,
                    AssetDownloadScreen.this.height, AssetScreenLayout.listY(),
                    AssetScreenLayout.ROW_HEIGHT, 0);
//?} else {
            super(AssetDownloadScreen.this.minecraft, AssetDownloadScreen.this.width,
                    AssetDownloadScreen.this.height, AssetScreenLayout.listY(),
                    AssetScreenLayout.ROW_HEIGHT);
//?}
            this.columnTitle = columnTitle;
//? if 1.20.1 {
            this.setRenderBackground(false);
//?}
        }

        /**
         * 定位（**两版 API 差异集中点 4**）：1.20.1 为 {@code updateSize(w,h,x,y)}，
         * 1.21.1 为 {@code updateSizeAndPosition(w,h,x)} + 由布局给 y（javap 实证）。
         */
        void place(int w, int h, int x, int y) {
//? if 1.20.1 {
            this.updateSize(w, h, x, y);
//?} else {
            this.updateSizeAndPosition(w, h, x);
            this.setY(y);
//?}
            this.colX = x;
            this.colY = y;
        }

        /** 公开包装：外层类不能直接调 protected 的 clearEntries/addEntry。 */
        void clearAll() {
            this.clearEntries();
        }

        /** 公开包装：追加一行。 */
        void append(PackRow row) {
            this.addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 12;
        }

        /** 列标题锚点（1.20.1 的 AbstractSelectionList 无 getX/getY ⇒ 自记坐标）。 */
        private int colX;
        private int colY;

//? if 1.20.1 {
        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.render(graphics, mouseX, mouseY, partialTick);
            renderColumnTitle(graphics);
        }
//?} else {
        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            renderColumnTitle(graphics);
        }
//?}

        /** 列标题（两端共用）。 */
        private void renderColumnTitle(GuiGraphics graphics) {
            graphics.drawCenteredString(Minecraft.getInstance().font, columnTitle,
                    colX + getWidth() / 2, colY - 12, 0xFFFFFF);
        }
    }

    /** 一行 = 一个素材包：名称 + 作者/许可/体积 + 行内按钮。 */
    private final class PackRow extends ObjectSelectionList.Entry<PackRow> {

        private final AssetPack pack;
        private final boolean installed;
        private final boolean active;
        private final boolean needsUpdate;
        /** 本帧鼠标悬停到的按钮动作（由 render 写入，mouseClicked 消费）。 */
        private Runnable hoveredAction;

        PackRow(AssetPack pack, boolean installed, boolean active, boolean needsUpdate) {
            this.pack = pack;
            this.installed = installed;
            this.active = active;
            this.needsUpdate = needsUpdate;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth,
                           int rowHeight, int mouseX, int mouseY, boolean hovered, float partialTick) {
            int x = left + 4;
            int y = top + 3;
            graphics.drawString(font, pack.name(), x, y, 0xFFFFFF);
            String meta = pack.authorsText() + " · v" + pack.version()
                    + (pack.fileSize() > 0 ? " · " + AssetScreenLayout.readableSize(pack.fileSize()) : "");
            graphics.drawString(font, meta, x, y + 11, 0xA0A0A0);
            graphics.drawString(font, pack.license(), x, y + 21, active ? 0x55FF55 : 0x707070);

            hoveredAction = null;
            int right = left + rowWidth;
            boolean showEnable = AssetScreenLayout.showEnableButton(installed, active);
            boolean showRemove = AssetScreenLayout.showRemoveButton(installed);
            boolean showDownload = AssetScreenLayout.showDownloadButton(installed, needsUpdate);
            int by = top + (rowHeight - AssetScreenLayout.BUTTON_HEIGHT) / 2;
            if (showDownload) {
                int bx = AssetScreenLayout.buttonX(right, AssetScreenLayout.BUTTON_MIN_WIDTH, 0);
                rowButton(graphics, needsUpdate ? BTN_UPDATE : BTN_DOWNLOAD, bx, by, mouseX, mouseY,
                        () -> download(pack));
            } else if (showEnable) {
                int bx = AssetScreenLayout.buttonX(right, AssetScreenLayout.BUTTON_MIN_WIDTH, 0);
                rowButton(graphics, BTN_ENABLE, bx, by, mouseX, mouseY, () -> enable(pack.id()));
            }
            if (showRemove) {
                int idx = showEnable ? 1 : 0;
                int bx = AssetScreenLayout.buttonX(right, AssetScreenLayout.BUTTON_MIN_WIDTH, idx);
                rowButton(graphics, BTN_REMOVE, bx, by, mouseX, mouseY, () -> remove(pack.id()));
            }
            if (active) {
                graphics.drawString(font, ENABLED_MARK, x, y + 31, 0x55FF55);
            }
        }

        /** 行内自绘按钮（原版 PackEntry 亦为自绘，规避子控件布局复杂度）。 */
        private void rowButton(GuiGraphics graphics, Component label, int x, int y,
                               int mouseX, int mouseY, Runnable action) {
            int w = AssetScreenLayout.BUTTON_MIN_WIDTH;
            int h = AssetScreenLayout.BUTTON_HEIGHT;
            boolean on = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            graphics.fill(x, y, x + w, y + h, on ? 0xFF7F7F7F : 0xFF5A5A5A);
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2B2B2B);
            graphics.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFF);
            if (on) {
                hoveredAction = action;
            }
        }

        @Override
        public boolean mouseClicked(double mx, double my, int button) {
            if (button == 0 && hoveredAction != null) {
                Runnable a = hoveredAction;
                hoveredAction = null;
                a.run();
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.literal(pack.name() + " — " + pack.attribution());
        }
    }
}
