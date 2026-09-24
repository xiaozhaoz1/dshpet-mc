package com.github.xiaozhaoz1.dshpet.config;

//? if 1.20.1 {
import net.minecraftforge.common.ForgeConfigSpec;
//?} else {
import net.neoforged.neoforge.common.ModConfigSpec;
//?}

/**
 * dshpet 配置（官方 ConfigSpec 标准写法，2026-09-23 标准化改造）。
 *
 * <p><b>为什么用官方 Spec</b>（社区标准审计 #3 差距）：此前是自写 JSON + 手写解析器，
 * 偏离社区标准。现按官方 Spec：结构/默认值/范围校验由框架负责（parse-don't-validate），
 * 自动生成带注释的 TOML，用户可直接编辑。</p>
 *
 * <p>多版本姿势：<b>条件导入 + 条件字段</b>（两版 Spec 类不同名，与 LMA 同款）。
 * 运行期可改：两版 {@code ConfigValue} 都有 {@code set(T)} + {@code save()}。</p>
 */
public final class DshPetConfig {

    /** 配置 Spec（注册在平台入口，文件名 {@code dshpet.toml}）。 */
//? if 1.20.1 {
    public static final ForgeConfigSpec SPEC;
//?} else {
    public static final ModConfigSpec SPEC;
//?}

    /** 调试日志开关（默认 false；开启后输出 [DEBUG-DSHPET] 摘要日志）。 */
//? if 1.20.1 {
    public static final ForgeConfigSpec.BooleanValue DEBUG;
//?} else {
    public static final ModConfigSpec.BooleanValue DEBUG;
//?}

    /** 当前启用的素材包 id（空串 = 未启用，回落到用户目录/jar 内置）。 */
//? if 1.20.1 {
    public static final ForgeConfigSpec.ConfigValue<String> ACTIVE_PACK;
//?} else {
    public static final ModConfigSpec.ConfigValue<String> ACTIVE_PACK;
//?}

    /** 宠物缩放模式：native（1 texel=1 物理像素，绝对锐利）/ pixel_perfect（整数倍）/ ratio（按屏比例，尺寸自由）。 */
//? if 1.20.1 {
    public static final ForgeConfigSpec.ConfigValue<String> PET_SCALE_MODE;
//?} else {
    public static final ModConfigSpec.ConfigValue<String> PET_SCALE_MODE;
//?}

    /** pixel_perfect 模式的整数倍 N（1 texel = N 物理像素；默认 2）。 */
//? if 1.20.1 {
    public static final ForgeConfigSpec.IntValue PET_PIXEL_PERFECT_MULTIPLE;
//?} else {
    public static final ModConfigSpec.IntValue PET_PIXEL_PERFECT_MULTIPLE;
//?}

    /** 素材下载单文件上限（字节；默认 32MB）。 */
//? if 1.20.1 {
    public static final ForgeConfigSpec.LongValue ASSET_MAX_BYTES;
//?} else {
    public static final ModConfigSpec.LongValue ASSET_MAX_BYTES;
//?}

    static {
//? if 1.20.1 {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
//?} else {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
//?}
        builder.comment("DSH Pet 配置").push("general");
        DEBUG = builder
                .comment("调试日志开关：开启后每秒输出一条 [DEBUG-DSHPET] 摘要日志",
                        "游戏内可用 /dshpet debug on|off|status 切换（即时生效并写回本文件）")
                .define("debug", false);
        ACTIVE_PACK = builder
                .comment("当前启用的素材包 id（对应 config/dshpet/assets/<id>/）",
                        "用 /dshpet model <id> 切换；留空表示不使用下载的素材包")
                .define("activePack", "");
        PET_SCALE_MODE = builder
                .comment("宠物缩放模式：",
                        "native        = 1 texel 对应 1 物理像素（完全不放大，最锐利、最小）",
                        "pixel_perfect = 1 texel 对应 N 个物理像素（N 为贴近目标尺寸的整数，整数倍放大）",
                        "ratio         = 按屏宽 15%/屏高上限 25% 计算尺寸，CPU 预重采样（尺寸自由）")
                .define("petScaleMode", "pixel_perfect");
        PET_PIXEL_PERFECT_MULTIPLE = builder
                .comment("pixel_perfect 模式的整数倍 N：1 texel = N 个物理像素（默认 2 ⇒ 72×92 素材显示为 144×184）",
                        "N=1 最小最锐利；N 越大越清晰可见但占用越大（上限 4）")
                .defineInRange("petPixelPerfectMultiple", 2, 1, 4);
        ASSET_MAX_BYTES = builder
                .comment("素材下载单文件上限（字节）；超过则拒绝下载（防超大/异常响应）")
                .defineInRange("assetMaxBytes", 32L * 1024L * 1024L, 64L * 1024L, 512L * 1024L * 1024L);
        builder.pop();
        SPEC = builder.build();
    }

    private DshPetConfig() {
    }
}
