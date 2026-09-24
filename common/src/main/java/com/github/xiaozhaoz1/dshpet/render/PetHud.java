package com.github.xiaozhaoz1.dshpet.render;

import com.github.xiaozhaoz1.dshpet.DshPetLog;
import com.github.xiaozhaoz1.dshpet.anim.AnimationClip;
import com.github.xiaozhaoz1.dshpet.core.Animator;
import com.github.xiaozhaoz1.dshpet.config.SharedConfig;
import com.github.xiaozhaoz1.dshpet.core.FrameGeometry;
import com.github.xiaozhaoz1.dshpet.core.ImageResampler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;

/**
 * M1 的 HUD 宠物：单实例 + 待机动画。
 *
 * <p>调用时机：平台入口的 HUD 钩子每帧调用（已确认在渲染线程 ⇒ 满足纹理线程纪律）。
 * 该钩子只在「HUD 渲染阶段」触发，<b>不含 Screen</b>（联网资料：{@code RenderGuiEvent.Post} =
 * "Fired after the HUD is rendered"；vanilla {@code Gui.render} 顺序为 HUD → Screen.render）</p>
 *
 * <p>因此"打开菜单时宠物变暗"不是层级问题：那时看到的是**打开界面前最后一帧**的残影，
 * 被界面背景盖住。本版显式在 {@code mc.screen != null} 时跳过绘制 —— 与上游语义一致
 * （下游桌宠在设置页同样不显示），也避免"残影看起来像变暗"。</p>
 */
public final class PetHud {

    /** M1 起手动画名（jar 内置素材）。 */
    /**
     * 起手动画名。
     * <p>临时诊断: 设为 "diag_marker"（256x160 坐标标尺图）以便实机测量渲染位置/尺寸/裁剪；
     * 诊断结束后改回 "dongzhangxiwang"。</p>
     */
    public static final String DEFAULT_ANIM = "dongzhangxiwang";

    /**
     * 角色**宽度** = 屏宽 × 该比例（**对齐上游 dsh-pet**：size 462 / 设计宽 1920 ≈ 24%）。
     *
     * <p>为什么用宽度：上游的尺寸语义就是"宽度像素 + 高度按 9:16 自动"，
     * 且实测按屏高定尺寸会在不同 GUI Scale 下反复失调（本会话"太大/太小"的根因）。</p>
     */
    /**
     * 角色宽度 = 屏宽 × 该比例（上限由 {@link #CHARACTER_MAX_SCREEN_HEIGHT_RATIO} 兜底）。
     *
     * <p>取值依据（2026-09-23 实机迭代，四次调参后定案）：上游 dsh-pet 的 462/1920=24% 是
     * <b>横屏窗口</b>语义（占高约 43%）；而用户 GUI 为 480×255 <b>近正方形</b>，
     * 同样 24% 宽 ⇒ 角色高 147px = <b>占屏高 58%</b>，观感"巨大"（用户实测反馈）。
     * ⇒ 必须同时用两条约束（宽比例 + 高上限）取更小者；本组取值在实测屏上得 50×64（占高 25%）。</p>
     */
    private static final float CHARACTER_WIDTH_RATIO = 0.15f;

    /**
     * 角色高度不得超过屏高的比例（**第二条约束，取更小者**）。
     *
     * <p>教训（本会话两次反复的真因）：只做一条约束必然在某个屏幕比例下失衡 ——
     * 最早只有宽度约束 ⇒ 近正方形 GUI 下过高；后来改成"高上限"独挡 ⇒ 又过小。
     * <b>宽 + 高双约束、取更小者</b>才是稳的。</p>
     */
    private static final float CHARACTER_MAX_SCREEN_HEIGHT_RATIO = 0.25f;

    /** 绝对上下界（px 宽）：极小屏不至于看不见，极大屏不至于夸张。 */
    private static final float MIN_CHARACTER_WIDTH_PX = 48f;
    private static final float MAX_CHARACTER_WIDTH_PX = 320f;

    /**
     * 重采样核（方案 B）：BICUBIC 在"清晰"与"无锯齿"之间最平衡；
     * 若想要绝对锐利（接受非整数倍下 texel 大小不均）可改 NEAREST。
     */
    private static final ImageResampler.Mode RESAMPLE_MODE = ImageResampler.Mode.BICUBIC;

    /** 距屏幕右下角的边距（像素）。 */
    private static final int MARGIN_X = 16;
    private static final int MARGIN_Y = 16;

    private static AnimationClip clip;
    private static Animator animator;
    /** 由内容盒推导的基准缩放（加载时算一次）。 */
    private static float scale = 1f;
    /** 内容盒高度/宽度（用于按屏宽比例换算缩放）。 */
    private static float scaleContentHeight = 1f;
    private static float scaleContentWidth = 1f;
    /** 最近一帧的实际缩放/绘制尺寸（诊断用）。 */
    private static float lastEffScale = 1f;
    private static int lastDrawW;
    private static int lastDrawH;
    private static int lastX;
    private static int lastY;
    private static int lastPhysW;
    private static int lastPhysH;
    private static float lastTexelsPerPx;
    private static int lastScreenW;
    private static int lastScreenH;
    private static int lastGuiScale;
    private static int lastContentMinX;
    private static int lastContentMinY;
    private static float lastTargetContentW;
    private static float lastMaxContentH;

    /** 累积的真实时间（纳秒）→ 换算 tick（HUD 帧率与 TPS 解耦）。 */
    private static long lastNanos;
    private static double tickAccumulator;
    private static boolean initFailed;
    /** 是否已发过"无素材"聊天提示（一次性，避免刷屏）。 */
    private static boolean hinted;

    /**
     * 诊断开关 —— **默认关**，且**游戏内可切换**（用户裁定 2026-09-23：不让用户去改 JVM 参数）。
     *
     * <p>三级来源（优先级从高到低）：</p>
     * <ol>
     *   <li>JVM 参数 {@code -Ddshpet.diag=true} / 环境变量 {@code DSHPET_DIAG=1}（开发/临时用，覆盖一切）</li>
     *   <li>{@code config/dshpet/config.json} 的 {@code "debug"} 字段（用户级持久化）</li>
     * </ol>
     *
     * <p>游戏内切换：{@code /dshpet debug on|off|status}（客户端命令，即时生效并落盘）。</p>
     * <p>输出前缀固定 {@code [DEBUG-DSHPET]} —— 排查结束后一条 grep 即可定位全部残留（铁律：用完删净）。</p>
     */
    private static boolean diagEnabled() {
        if (FORCE_DIAG) {
            return true; // JVM/环境变量强制开启（覆盖配置）
        }
        return com.github.xiaozhaoz1.dshpet.config.SharedConfig.debug();
    }

    /** JVM/环境变量级强制开关（默认 false）。 */
    private static final boolean FORCE_DIAG = Boolean.getBoolean("dshpet.diag")
            || "1".equals(System.getenv("DSHPET_DIAG"))
            || "true".equalsIgnoreCase(System.getenv("DSHPET_DIAG"));

    /** 诊断输出间隔（秒）。 */
    private static final double DIAG_INTERVAL_SEC = 1.0;

    private static long lastDiagNanos;
    private static int diagFramesSeen;
    /** 诊断: 上一秒末的帧索引（用于报告"本秒帧索引变化"，即动画是否真的在推进）。 */
    private static int diagPrevFrame = -1;

    private PetHud() {
    }

    /** 世界卸载/断开时由平台入口调用：释放纹理并重置状态。 */
    public static void reset() {
        SpriteBackend.releaseAll();
        clip = null;
        animator = null;
        tickAccumulator = 0;
        lastNanos = 0;
        lastDiagNanos = 0;
        diagFramesSeen = 0;
        initFailed = false;
        hinted = false;
    }

    /**
     * 每帧绘制（渲染线程）。
     *
     * <p>调用来源两个（平台入口分别注册）：无界面时的 HUD 阶段、界面渲染之后的
     * {@code ScreenEvent.Render.Post}。本方法<b>不区分场景</b> —— 用户裁定
     * 「进入世界后任何时候都能看到」（含暂停菜单/设置界面之上）。</p>
     */
    public static void render(GuiGraphics graphics, Path userAnimDir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return; // 未进入世界不画
        }
        if (!ensureLoaded(userAnimDir)) {
            return;
        }
        advance();
        diagTick();

        // ── 缩放与绘制（三模式，用户 2026-09-23 裁定可切换） ──────────────────────
        //   native        : 1 texel = 1 物理像素（**完全不放大** ⇒ 绝对锐利）
        //   pixel_perfect : 1 texel = N 物理像素（整数倍 ⇒ 锐利，尺寸贴近目标）
        //   ratio         : 按屏比例算尺寸 + CPU 预重采样（尺寸自由，略带软化）
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();
        float guiScale = Math.max(1f, (float) Minecraft.getInstance().getWindow().getGuiScale());
        float effScale = scaleForScreen(screenW, screenH);      // 仅 ratio 模式用
        SharedConfig.PetScaleMode mode = SharedConfig.petScaleMode();
        int texW = clip.width();
        int texH = clip.height();
        int physW;
        int physH;
        switch (mode) {
            case NATIVE -> {
                physW = texW;
                physH = texH;
            }
            case PIXEL_PERFECT -> {
                // 整数倍 N **由配置决定**（petPixelPerfectMultiple，默认 2）—— 2026-09-23 用户裁定：
                // 原来"由目标尺寸推导 N"会让窗口/guiScale 变化时 N 静默跳档，用户无法确定控制。
                int n = SharedConfig.petPixelPerfectMultiple();
                physW = texW * n;
                physH = texH * n;
            }
            default -> {
                // ratio：期望尺寸 → 物理像素，并吸附到 guiScale 整数倍（便于 1:1 贴出）
                physW = ImageResampler.snapToGranularity(texW * effScale * guiScale, (int) guiScale);
                physH = ImageResampler.snapToGranularity(texH * effScale * guiScale, (int) guiScale);
            }
        }

        // 纹理按目标物理尺寸准备（native/pixel_perfect 时尺寸=素材整数倍 ⇒ NEAREST 即可零损失；
        // ratio 时走 CPU 重采样吸收非整数倍）
        ImageResampler.Mode kernel = (mode == SharedConfig.PetScaleMode.RATIO)
                ? RESAMPLE_MODE : ImageResampler.Mode.NEAREST;
        ResourceLocation tex = SpriteBackend.textureFor(clip, animator.frameIndex(), physW, physH, kernel);

        // 用 pose 把坐标系换成"物理像素" ⇒ 可按物理像素精确定位/绘制（不受整数 GUI 限制）
        int marginPx = Math.round(MARGIN_X * guiScale);
        float physX = Minecraft.getInstance().getWindow().getWidth() - physW - marginPx;
        float physY = Minecraft.getInstance().getWindow().getHeight() - physH - marginPx;
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(physX / guiScale, physY / guiScale, 0f);
        pose.scale(1f / guiScale, 1f / guiScale, 1f);
        graphics.blit(tex, 0, 0, physW, physH, 0f, 0f, physW, physH, physW, physH);
        pose.popPose();

        // 诊断字段（GUI 尺寸供人对照；物理尺寸是真正的绘制量）
        int drawW = Math.max(1, Math.round(physW / guiScale));
        int drawH = Math.max(1, Math.round(physH / guiScale));
        int x = Math.round(physX / guiScale);
        int y = Math.round(physY / guiScale);
        lastEffScale = effScale;
        lastDrawW = drawW;
        lastDrawH = drawH;
        lastPhysW = drawW * (int) guiScale;
        lastPhysH = drawH * (int) guiScale;
        lastTexelsPerPx = clip.height() > 0 ? (float) lastPhysH / clip.height() : 0f;
        lastX = x;
        lastY = y;
        lastScreenW = screenW;
        lastScreenH = screenH;
        lastGuiScale = (int) Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** 惰性加载：首次渲染时解码整条动画（≈80ms），之后复用。 */
    private static boolean ensureLoaded(Path userAnimDir) {
        if (clip != null) {
            return true;
        }
        if (initFailed) {
            return false;
        }
        AnimationClip loaded = SpriteBackend.load(currentAnimName(), userAnimDir);
        if (loaded == null) {
            initFailed = true;
            DshPetLog.warn(DshPetLog.ASSET,
                    "找不到可用动画 '{}' ⇒ 本会话不显示宠物。安装素材：/dshpet assets default"
                            + "（或把 gif 放进 {}）", currentAnimName(), userAnimDir);
            hintOnce();
            return false;
        }
        clip = loaded;
        animator = new Animator(clip.frameCount(), Animator.ticksPerFrameFor(clip.fps()), true);
        scale = computeScale(clip);
        lastNanos = System.nanoTime();
        DshPetLog.info(DshPetLog.ASSET, "已加载动画 '{}'（{} 帧 / 画布 {}x{} / {} fps / 来源 {} / 显示缩放 {}）",
                clip.name(), clip.frameCount(), clip.width(), clip.height(), clip.fps(), clip.source(),
                String.format("%.2f", scale));
        return true;
    }

    /**
     * 当前要加载的动画名：**启用素材包里的第一个可用动画**，否则内置默认名。
     *
     * <p>为什么不能写死动画名（2026-09-23 素材改为下载后）：包内文件名由包决定，
     * 不同模型名字不同 ⇒ 写死会导致"换了模型就找不到"。</p>
     */
    private static String currentAnimName() {
        // 优先：启用包声明的 idle 动画名（pack.json 的 animations.idle）
        String declared = com.github.xiaozhaoz1.dshpet.config.asset.AssetStore.activePreferredAnimation();
        if (!declared.isBlank()) {
            return declared;
        }
        Path packDir = com.github.xiaozhaoz1.dshpet.config.asset.AssetStore.activeAnimationsDir();
        if (packDir != null) {
            // 声明缺失时优先用内置默认名（若包里有它）—— 不能直接取"目录字母序第一个"，
            // 否则换包/换声明会静默变成另一段动画（2026-09-23 实机 bug）
            for (String ext : new String[] {".gif", ".png"}) {
                if (java.nio.file.Files.isRegularFile(packDir.resolve(DEFAULT_ANIM + ext))) {
                    return DEFAULT_ANIM;
                }
            }
            try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(packDir)) {
                java.util.Optional<Path> first = files
                        .filter(java.nio.file.Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                            return n.endsWith(".gif") || n.endsWith(".png");
                        })
                        .sorted()
                        .findFirst();
                if (first.isPresent()) {
                    String fileName = first.get().getFileName().toString();
                    int dot = fileName.lastIndexOf('.');
                    return dot > 0 ? fileName.substring(0, dot) : fileName;
                }
            } catch (Throwable ignored) {
                // 读目录失败 ⇒ 回落默认名（不打断渲染）
            }
        }
        return DEFAULT_ANIM;
    }

    /** 无素材时的一次性聊天提示（不刷屏；用户裁定：首启空态可接受，但须给出可操作指引）。 */
    private static void hintOnce() {
        if (hinted) {
            return;
        }
        hinted = true;
        try {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable("dshpet.hint.no_assets"), false);
            }
        } catch (Throwable ignored) {
            // 提示失败无碍（早期阶段 player 可能为空）
        }
    }

    /**
     * 按屏高算缩放（唯一入口）：目标高度 = guiHeight × 比例，夹到绝对边界，再除以内容盒高度。
     *
     * <p>实机取证（2026-09-23）：用户 GUI 逻辑分辨率 480×255 ⇒ 固定 110px = 屏高 43%（显得巨大），
     * 故改为比例驱动；同时保留绝对上下界防极端分辨率。</p>
     */
    /**
     * 按屏宽比例算画布缩放（唯一入口）。
     *
     * <p>几何：素材的内容盒（character）在画布内有一个占位比 {@code contentW/canvasW}；
     * 期望角色宽 = 屏宽 × 比例 ⇒ 画布缩放 = 期望角色宽 / 内容盒宽。</p>
     *
     * <p>兜底：缩放后**画布高度**不得超过屏高（极端窄屏/超宽素材防画出界）。</p>
     */
    /**
     * 画布缩放 = **两条约束取更小者**（唯一入口）：
     * <ol>
     *   <li>按屏宽比例：期望角色宽 = 屏宽 × {@link #CHARACTER_WIDTH_RATIO}（夹绝对边界）</li>
     *   <li>按屏高上限：角色高 ≤ 屏高 × {@link #CHARACTER_MAX_SCREEN_HEIGHT_RATIO}</li>
     * </ol>
     *
     * <p>两次实机反复的教训：任一单独约束都会在某些屏幕比例下失衡 ⇒ 必须双约束取小。</p>
     */
    private static float scaleForScreen(int screenW, int screenH) {
        if (clip == null) {
            return 1f;
        }
        int contentW = Math.max(1, (int) scaleContentWidth);
        int contentH = Math.max(1, (int) scaleContentHeight);
        lastTargetContentW = FrameGeometry.targetWidthFor(screenW, CHARACTER_WIDTH_RATIO,
                MIN_CHARACTER_WIDTH_PX, MAX_CHARACTER_WIDTH_PX);
        lastMaxContentH = screenH * CHARACTER_MAX_SCREEN_HEIGHT_RATIO;
        float s = FrameGeometry.scaleForViewport(screenW, screenH, contentW, contentH,
                CHARACTER_WIDTH_RATIO, CHARACTER_MAX_SCREEN_HEIGHT_RATIO,
                MIN_CHARACTER_WIDTH_PX, MAX_CHARACTER_WIDTH_PX);
        // 兜底: 画布整体不得超出屏幕（极端素材/极小窗口）
        float maxCanvasH = Math.max(1f, screenH - 8f);
        if (clip.height() * s > maxCanvasH) {
            s = maxCanvasH / clip.height();
        }
        return s;
    }

    /**
     * 由**内容盒**（所有帧不透明像素的并集边界）推导显示缩放。
     *
     * <p>为什么不用画布尺寸：上游素材画布 220×124，但角色实际只占约 72×92（本机实测，
     * 见 docs/AUDIT-2026-09-22.md）——按画布缩放会让角色显示过大（用户实机反馈"太大了"即此因）。</p>
     */
    private static float computeScale(AnimationClip c) {
        FrameGeometry.Bounds b = FrameGeometry.unionBounds(c.width(), c.height(), c.frames());
        if (b == null) {
            return 1f; // 全透明素材（异常）: 退回 1:1，不崩
        }
        scaleContentHeight = b.height();
        scaleContentWidth = b.width();
        lastContentMinX = b.minX();
        lastContentMinY = b.minY();
        DshPetLog.info(DshPetLog.PET, "内容盒 {}x{} @({},{}) ⇒ 内容宽 {}px（缩放每帧按 屏宽×{} 换算）",
                b.width(), b.height(), b.minX(), b.minY(), b.width(),
                String.format("%.2f", CHARACTER_WIDTH_RATIO));
        return 1f;
    }

    /** 按真实时间推进时间轴（HUD 帧率可变，用时间累加而非"每帧 1 tick"，避免高帧率下动画变快）。 */
    private static void advance() {
        long now = System.nanoTime();
        if (lastNanos == 0) {
            lastNanos = now;
            return;
        }
        double deltaSec = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (deltaSec > 0.25) {
            deltaSec = 0.25; // 卡顿/切窗口回来时防止一次跳过多帧
        }
        tickAccumulator += deltaSec * 20.0;
        int guard = 0;
        while (tickAccumulator >= 1.0 && guard++ < 100) {
            tickAccumulator -= 1.0;
            if (animator.tick() == Animator.Advance.FINISHED) {
                animator.restart(); // M1: 单动画循环（M4 接动画链后由权重选择接替）
            }
        }
    }

    /**
     * 诊断输出（**默认关**；铁律：热路径日志必须可降级 + 唯一前缀 + 用完删净）。
     *
     * <p>一次输出把定位所需字段写满（避免"一轮只加一个字段"的来回取证）：
     * 屏幕宽高、GUI Scale、素材画布/内容盒、期望尺寸、实际绘制尺寸与位置、缩放、
     * 帧索引（含与上一秒对比 ⇒ 直接判定动画是否推进）、实际渲染帧率。</p>
     */
    private static void diagTick() {
        if (!diagEnabled()) {
            return;
        }
        long now = System.nanoTime();
        diagFramesSeen++;
        if (lastDiagNanos == 0) {
            lastDiagNanos = now;
            return;
        }
        double elapsed = (now - lastDiagNanos) / 1_000_000_000.0;
        if (elapsed < DIAG_INTERVAL_SEC) {
            return;
        }
        int frame = animator.frameIndex() + 1;
        String frameDelta = (diagPrevFrame < 0) ? "n/a"
                : (frame == diagPrevFrame ? "STUCK!" : "advancing");
        DshPetLog.info(DshPetLog.DEBUG, "screen={}x{} guiScale={} canvas={}x{} content={}x{}@({},{}) "
                        + "targetW={}px draw={}x{}@({},{}) phys={}x{} texel/px={} scale={} "
                        + "frame={}/{} ({}) fps≈{} anim={}",
                lastScreenW, lastScreenH, lastGuiScale,
                clip.width(), clip.height(),
                (int) scaleContentWidth, (int) scaleContentHeight, lastContentMinX, lastContentMinY,
                Math.round(lastTargetContentW), lastDrawW, lastDrawH, lastX, lastY,
                lastPhysW, lastPhysH, String.format("%.3f", lastTexelsPerPx),
                String.format("%.3f", lastEffScale),
                frame, clip.frameCount(), frameDelta,
                Math.round(diagFramesSeen / elapsed), clip.name());
        diagPrevFrame = frame;
        lastDiagNanos = now;
        diagFramesSeen = 0;
    }
}
