package com.github.xiaozhaoz1.dshpet.screen;

import com.github.xiaozhaoz1.dshpet.DshPet;
import com.github.xiaozhaoz1.dshpet.DshPetLog;
import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.config.SharedConfig;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetStore;
import com.github.xiaozhaoz1.dshpet.render.PetHud;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * dshpet 配置屏 —— **两平台共用**（方案 A，用户 2026-09-23 裁定）。
 *
 * <p><b>坐标权威来源</b>：{@code docs/PLAN.md} §4.3（面板 320×292、底栏 5 按钮 x=8/68/128/188/248、
 * 按钮 52×18）。本类只做"把 §4.3 的槽位填上当前已有的配置项"，几何全部走
 * {@link ConfigScreenLayout}（纯函数、有单测对照 §4.3 逐值）。</p>
 *
 * <p><b>与 §4.3 的差异（如实标注）</b>：§4.3 描述的是 M6「完整配置屏」（含动画列表/预览/
 * 每动画参数/宠物实例）。本轮只实现**当前已有的配置项**（缩放模式、整数倍 N、调试日志、
 * 启用素材包、署名页脚），其余槽位留白待 M6 填充 —— 这样几何与 §4.3 同源，M6 扩展时不需要
 * 重排坐标（避免"图文冲突/布局漂移"）。</p>
 *
 * <p><b>注册</b>：forge 1.20.1 {@code ConfigScreenHandler.ConfigScreenFactory}；
 * neoforge 1.21.1 {@code IConfigScreenFactory}（两处注册见平台入口类）。</p>
 */
public final class DshPetConfigScreen extends Screen {

    /** 标题（§4.3 y=8 左对齐）。 */
    private static final Component TITLE = Component.translatable("dshpet.config.title");
    /** 底栏按钮文案（§4.3 顺序：重新扫描 / 打开素材文件夹 / 保存 / 重置全部 / 完成）。 */
    private static final Component BTN_RESCAN = Component.translatable("dshpet.config.rescan");
    private static final Component BTN_FOLDER = Component.translatable("dshpet.config.open_folder");
    private static final Component BTN_SAVE = Component.translatable("dshpet.config.save");
    private static final Component BTN_RESET = Component.translatable("dshpet.config.reset");
    private static final Component BTN_DONE = CommonComponents.GUI_DONE;

    private final Screen parent;
    /** 底栏按钮（§4.3 固定 5 个槽位）。 */
    private final List<Button> footerButtons = new ArrayList<>();
    /** 状态提示（保存/重置/扫描后显示一行）。 */
    private Component status = Component.empty();
    /** 署名（来自已安装包 pack.json；无包时用上游默认口径）。 */
    private Component attribution = Component.empty();

    /** 悬停/最近操作的说明（显示在标题下方，供用户理解当前项）。 */
    private Component hint = Component.empty();

    public DshPetConfigScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        footerButtons.clear();
        int panelX = ConfigScreenLayout.panelX(width);
        int panelY = ConfigScreenLayout.panelY(height);

        // ── 内容行（§4.3 槽位：左标签 x=8，控件 x=108 起） ──────────────────────
        int row = 0;
        addRenderableWidget(Button.builder(scaleModeLabel(), b -> {
                    cycleScaleMode();
                    rebuild();
                })
                .bounds(panelX + 108, panelY + ConfigScreenLayout.rowY(row), 100, ConfigScreenLayout.CONTROL_H)
                .build());
        row++;
        // N 行：[◂] 值 [▸]（§4.3 风格：小步进按钮）
        addRenderableWidget(Button.builder(Component.literal("◂"), b -> {
                    SharedConfig.setPetPixelPerfectMultiple(SharedConfig.petPixelPerfectMultiple() - 1);
                    rebuild();
                })
                .bounds(panelX + 108, panelY + ConfigScreenLayout.rowY(row), 20, ConfigScreenLayout.CONTROL_H)
                .build());
        addRenderableWidget(Button.builder(Component.literal("▸"), b -> {
                    SharedConfig.setPetPixelPerfectMultiple(SharedConfig.petPixelPerfectMultiple() + 1);
                    rebuild();
                })
                .bounds(panelX + 192, panelY + ConfigScreenLayout.rowY(row), 20, ConfigScreenLayout.CONTROL_H)
                .build());
        row++;
        addRenderableWidget(Button.builder(debugLabel(), b -> {
                    SharedConfig.setDebug(!SharedConfig.debug());
                    rebuild();
                })
                .bounds(panelX + 108, panelY + ConfigScreenLayout.rowY(row), 60, ConfigScreenLayout.CONTROL_H)
                .build());
        row++;
        addRenderableWidget(Button.builder(packLabel(), b -> {
                    cycleActivePack();
                    rebuild();
                })
                .bounds(panelX + 108, panelY + ConfigScreenLayout.rowY(row), 150, ConfigScreenLayout.CONTROL_H)
                .build());

        // ── 底栏 5 按钮（§4.3：x=8/68/128/188/248，52×18） ────────────────────
        int footerY = panelY + ConfigScreenLayout.footerY(ConfigScreenLayout.panelH(height));
        String[] keys = {"rescan", "folder", "save", "reset", "done"};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            Component label = switch (key) {
                case "rescan" -> BTN_RESCAN;
                case "folder" -> BTN_FOLDER;
                case "save" -> BTN_SAVE;
                case "reset" -> BTN_RESET;
                default -> BTN_DONE;
            };
            Button btn = Button.builder(label, b -> onFooter(key))
                    .bounds(panelX + ConfigScreenLayout.footerBtnX(i), footerY,
                            ConfigScreenLayout.FOOTER_BTN_W, ConfigScreenLayout.FOOTER_BTN_H)
                    .build();
            footerButtons.add(addRenderableWidget(btn));
        }
        refreshAttribution();
    }

    /** 重新构建控件（值变化后刷新按钮文案）。 */
    private void rebuild() {
        clearWidgets();
        footerButtons.clear();
        init();
    }

    // ── 动作 ──────────────────────────────────────────────────────────────

    private void cycleScaleMode() {
        SharedConfig.PetScaleMode cur = SharedConfig.petScaleMode();
        SharedConfig.PetScaleMode next = switch (cur) {
            case NATIVE -> SharedConfig.PetScaleMode.PIXEL_PERFECT;
            case PIXEL_PERFECT -> SharedConfig.PetScaleMode.RATIO;
            case RATIO -> SharedConfig.PetScaleMode.NATIVE;
        };
        SharedConfig.setPetScaleMode(next);
        PetHud.reset(); // 立即重载（尺寸/模式变化）
        status = Component.translatable("dshpet.config.saved");
    }

    private void cycleActivePack() {
        List<AssetStore.InstalledPack> packs = AssetStore.installed();
        List<String> ids = new ArrayList<>();
        ids.add(""); // 「（无）」= 不启用下载包
        for (AssetStore.InstalledPack p : packs) {
            ids.add(p.id());
        }
        String cur = SharedConfig.activePack();
        int idx = ids.indexOf(cur == null ? "" : cur);
        String next = ids.get((idx + 1) % ids.size());
        SharedConfig.setActivePack(next);
        PetHud.reset();
        status = Component.translatable("dshpet.config.saved");
    }

    private void onFooter(String key) {
        switch (key) {
            case "rescan" -> {
                PetHud.reset();
                status = Component.translatable("dshpet.config.rescanned");
                DshPetLog.info(DshPetLog.CMD, "配置屏：已重新扫描素材");
            }
            case "folder" -> {
                try {
                    // §4.4：两版都只有 openFile/openUri（无 openPath）
                    Util.getPlatform().openFile(ConfigPaths.assetsDir().toFile());
                } catch (Throwable t) {
                    DshPetLog.warn(DshPetLog.CFG, "打开素材目录失败: {}", t.toString());
                }
            }
            case "save" -> {
                // 各项 setter 已即时落盘；此处显式再存一次并给反馈
                SharedConfig.setDebug(SharedConfig.debug());
                status = Component.translatable("dshpet.config.saved");
            }
            case "reset" -> {
                SharedConfig.setPetScaleMode(SharedConfig.DEFAULT_SCALE_MODE);
                SharedConfig.setPetPixelPerfectMultiple(2);
                SharedConfig.setDebug(false);
                SharedConfig.setActivePack("");
                PetHud.reset();
                status = Component.translatable("dshpet.config.reset_done");
                rebuild();
            }
            default -> onClose();
        }
    }

    /** 署名（来自 pack.json 单一来源；无包时给上游默认口径）。 */
    private void refreshAttribution() {
        List<AssetStore.InstalledPack> installed = AssetStore.installed();
        attribution = installed.isEmpty()
                ? Component.translatable("dshpet.screen.assets.attribution_default")
                : Component.literal(installed.get(0).pack().attribution());
    }

    // ── 文案 ──────────────────────────────────────────────────────────────

    private Component scaleModeLabel() {
        return switch (SharedConfig.petScaleMode()) {
            case NATIVE -> Component.translatable("dshpet.config.mode.native");
            case PIXEL_PERFECT -> Component.translatable("dshpet.config.mode.pixel_perfect",
                    SharedConfig.petPixelPerfectMultiple());
            case RATIO -> Component.translatable("dshpet.config.mode.ratio");
        };
    }

    private Component debugLabel() {
        return SharedConfig.debug()
                ? Component.translatable("dshpet.config.on")
                : Component.translatable("dshpet.config.off");
    }

    private Component packLabel() {
        String id = SharedConfig.activePack();
        return Component.literal(id == null || id.isBlank()
                ? Component.translatable("dshpet.config.pack.none").getString()
                : id);
    }

    // ── 渲染（严格按 §4.3 坐标） ───────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int px = ConfigScreenLayout.panelX(width);
        int py = ConfigScreenLayout.panelY(height);
        int pw = ConfigScreenLayout.PANEL_W;
        int ph = ConfigScreenLayout.panelH(height);
        int footerY = py + ConfigScreenLayout.footerY(ph);

        // ① 面板底：必须在 super.render **之前**画（否则会盖住 super 画出来的按钮）
        graphics.fill(px, py, px + pw, py + ph, 0xC0101010);

        // ② super.render：内部会调 renderBackground（1.21.1 含 processBlurEffect 全屏模糊）+ 画 widgets。
        //    ⚠️ 绝不要自己再调一次 renderBackground（2026-09-23 实机 bug，错题 #D17）：
        //    那会把"我自己已画的文字"一起模糊掉，而按钮因在其后绘制仍是清晰的
        //    ⇒ 实机表现"背景字模糊、只有按钮亮"。
        super.render(graphics, mouseX, mouseY, partialTick);

        // ③ 以下全部在 super 之后 ⇒ 不会被模糊，保持清晰
        graphics.fill(px, py, px + pw, py + 1, 0xFF808080);                  // 上边
        graphics.fill(px, py + ph - 1, px + pw, py + ph, 0xFF808080);        // 下边
        graphics.drawString(font, getTitle(), px + 8, py + 8, 0xFFFFFF);

        int labelColor = 0xC0C0C0;
        graphics.drawString(font, Component.translatable("dshpet.config.row.mode"), px + 8,
                py + ConfigScreenLayout.rowY(0) + 5, labelColor);
        graphics.drawString(font, Component.translatable("dshpet.config.row.multiple"), px + 8,
                py + ConfigScreenLayout.rowY(1) + 5, labelColor);
        graphics.drawString(font, Component.translatable("dshpet.config.row.debug"), px + 8,
                py + ConfigScreenLayout.rowY(2) + 5, labelColor);
        graphics.drawString(font, Component.translatable("dshpet.config.row.pack"), px + 8,
                py + ConfigScreenLayout.rowY(3) + 5, labelColor);

        // N 值（两按钮之间的读数）
        graphics.drawString(font, Component.literal(SharedConfig.petPixelPerfectMultiple() + "×"),
                px + 134, py + ConfigScreenLayout.rowY(1) + 5, 0xFFFFFF);

        // 署名 + 版本 + 状态（底栏之上两行；全部在 super 之后 ⇒ 清晰）
        graphics.drawString(font, attribution, px + 8, footerY - 10, 0x909090);
        graphics.drawString(font, versionLine(), px + 8, footerY - 20, 0x707070);
        if (!status.getString().isEmpty()) {
            graphics.drawString(font, status, px + 8, footerY - 30, 0x55FF55);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    /** 供命令/键位复用：创建实例（父屏可为 null）。 */
    public static Screen create(Screen parent) {
        return new DshPetConfigScreen(parent);
    }

    /** 版本信息（页脚用）。 */
    public static String versionLine() {
        return DshPet.MOD_ID + " " + DshPet.VERSION + " · config/dshpet.toml";
    }
}
