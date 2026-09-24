package com.github.xiaozhaoz1.dshpet.config;

import com.github.xiaozhaoz1.dshpet.DshPetLog;

/**
 * 配置读写门面 —— 唯一出口（供业务层调用，隔离平台 Spec 类型差异）。
 *
 * <p>存储为官方 ConfigSpec（{@link DshPetConfig}）；本类只提供<b>语义化读写</b>。
 * 读路径一律 {@code catch Throwable} + 返回安全默认值（渲染热路径不能被配置异常打断，错题 #335 同族）。</p>
 */
public final class SharedConfig {

    /** activePack 的默认值（未启用任何下载包）。 */
    public static final String NO_ACTIVE_PACK = "";

    private SharedConfig() {
    }

    /** 调试日志开关（默认关）。 */
    public static boolean debug() {
        try {
            return DshPetConfig.DEBUG.get();
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.CFG, "读取 debug 配置失败，按关闭处理: {}", t.toString());
            return false;
        }
    }

    /** 设置调试开关并立即写回 TOML（游戏内命令调用）。 */
    public static void setDebug(boolean value) {
        try {
            DshPetConfig.DEBUG.set(value);
            DshPetConfig.DEBUG.save();
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.CFG, "写入 debug 配置失败（本会话内仍按内存值生效）: {}", t.toString());
        }
    }

    /** 当前启用的素材包 id（未启用/读取失败 ⇒ 空串）。 */
    public static String activePack() {
        try {
            String v = DshPetConfig.ACTIVE_PACK.get();
            return v == null ? NO_ACTIVE_PACK : v.trim();
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.CFG, "读取 activePack 失败，按未启用处理: {}", t.toString());
            return NO_ACTIVE_PACK;
        }
    }

    /** 切换启用素材包（空串 = 取消启用）。 */
    public static void setActivePack(String packId) {
        try {
            String v = packId == null ? NO_ACTIVE_PACK : packId.trim();
            DshPetConfig.ACTIVE_PACK.set(v);
            DshPetConfig.ACTIVE_PACK.save();
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.CFG, "写入 activePack 失败（本会话内仍按内存值生效）: {}", t.toString());
        }
    }

    /**
     * pixel_perfect 模式的整数倍 N（1..4；读取失败/越界 ⇒ **Spec 默认值 2**）。
     *
     * <p>铁律（2026-09-23 用户点名）：**回落必须是 Spec 的默认值**，不是硬编码常量 ——
     * 否则"代码默认改了但用户看到旧值/另一值"，排查困难（本会话已踩过）。</p>
     */
    public static int petPixelPerfectMultiple() {
        try {
            return clamp(DshPetConfig.PET_PIXEL_PERFECT_MULTIPLE.get(), 1, 4);
        } catch (Throwable t) {
            int def = 2;
            DshPetLog.warn(DshPetLog.CFG, "读取 petPixelPerfectMultiple 失败，回落默认值 {}: {}",
                    def, t.toString());
            return def;
        }
    }

    /** 设置整数倍 N（越界自动夹到 1..4）并落盘。 */
    public static void setPetPixelPerfectMultiple(int n) {
        try {
            DshPetConfig.PET_PIXEL_PERFECT_MULTIPLE.set(clamp(n, 1, 4));
            DshPetConfig.PET_PIXEL_PERFECT_MULTIPLE.save();
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.CFG, "写入 petPixelPerfectMultiple 失败: {}", t.toString());
        }
    }

    /** 把值夹到 [lo, hi]。 */
    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 宠物缩放模式（未知/读取失败 ⇒ **Spec 默认值 pixel_perfect**）。 */
    public static PetScaleMode petScaleMode() {
        try {
            return PetScaleMode.parseOrDefault(DshPetConfig.PET_SCALE_MODE.get());
        } catch (Throwable t) {
            // 铁律：回落 Spec 默认值（pixel_perfect），并**留日志**（不静默）
            DshPetLog.warn(DshPetLog.CFG, "读取 petScaleMode 失败，回落默认值 {}: {}",
                    DEFAULT_SCALE_MODE, t.toString());
            return DEFAULT_SCALE_MODE;
        }
    }

    /** 设置缩放模式并落盘。 */
    public static void setPetScaleMode(PetScaleMode mode) {
        try {
            DshPetConfig.PET_SCALE_MODE.set(mode.name().toLowerCase(java.util.Locale.ROOT));
            DshPetConfig.PET_SCALE_MODE.save();
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.CFG, "写入 petScaleMode 失败: {}", t.toString());
        }
    }

    /** Spec 默认缩放模式（与 {@code DshPetConfig} 的 define 默认值必须一致）。 */
    public static final PetScaleMode DEFAULT_SCALE_MODE = PetScaleMode.PIXEL_PERFECT;

    /** 缩放模式取值。 */
    public enum PetScaleMode {
        /** 1 texel = 1 物理像素（不放大）。 */
        NATIVE,
        /** 1 texel = N 物理像素（整数倍）。 */
        PIXEL_PERFECT,
        /** 按屏比例 + CPU 预重采样。 */
        RATIO;

        /** 解析；无法识别时返回 **Spec 默认值**（而非硬编码某个模式）。 */
        static PetScaleMode parseOrDefault(String v) {
            if (v == null) {
                return DEFAULT_SCALE_MODE;
            }
            return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "ratio" -> RATIO;
                case "pixel_perfect", "pixelperfect", "perfect" -> PIXEL_PERFECT;
                case "native" -> NATIVE;
                default -> DEFAULT_SCALE_MODE;
            };
        }
    }

    /** 素材下载单文件上限（字节；读取失败 ⇒ 32MB 兜底）。 */
    public static long assetMaxBytes() {
        try {
            Long v = DshPetConfig.ASSET_MAX_BYTES.get();
            return v == null || v <= 0 ? 32L * 1024L * 1024L : v;
        } catch (Throwable t) {
            return 32L * 1024L * 1024L;
        }
    }
}
