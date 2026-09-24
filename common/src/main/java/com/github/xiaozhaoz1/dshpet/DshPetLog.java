package com.github.xiaozhaoz1.dshpet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志门面 —— **唯一出口 + canonical 前缀表**（照社区惯例与 LMA 同款裁定：
 * 「每 mod 一个 Logger + 固定子系统前缀表 + 守护断言」）。
 *
 * <p>为什么要有这张表（2026-09-23 标准审计的 #1 差距）：此前日志前缀是散写字符串
 * （`[dshpet]` / `[DEBUG-DSHPET]` 各写各的），既不好 grep 也无法守护。
 * 现在**表外前缀视为违规**，由 {@code LogPrefixGuardTest} 静态断言。</p>
 *
 * <p>前缀命名规则：{@code [DSHPET/<子系统>]} 全大写下划线；调试专用前缀独立为 {@code [DEBUG-DSHPET]}。</p>
 */
public final class DshPetLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("dshpet");

    /** 前缀：宠物渲染/动画。 */
    public static final String PET = "[DSHPET/PET]";
    /** 前缀：配置读写。 */
    public static final String CFG = "[DSHPET/CFG]";
    /** 前缀：客户端命令。 */
    public static final String CMD = "[DSHPET/CMD]";
    /** 前缀：素材加载（内置资源/用户目录解析与回退）。 */
    public static final String ASSET = "[DSHPET/ASSET]";
    /** 前缀：诊断（默认关；铁律要求唯一前缀便于清理）。 */
    public static final String DEBUG = "[DEBUG-DSHPET]";

    /** canonical 前缀全集 —— 守护测试与文档共用此单一来源。 */
    public static final String[] ALL_PREFIXES = {PET, CFG, CMD, ASSET, DEBUG};

    private DshPetLog() {
    }

    public static void info(String prefix, String msg, Object... args) {
        LOGGER.info(prefix + " " + msg, args);
    }

    public static void warn(String prefix, String msg, Object... args) {
        LOGGER.warn(prefix + " " + msg, args);
    }

    public static void debug(String prefix, String msg, Object... args) {
        LOGGER.debug(prefix + " " + msg, args);
    }

    /** 带异常对象的警告（保留堆栈，便于定位解码/IO 失败原因）。 */
    public static void warn(String prefix, String msg, Throwable t) {
        LOGGER.warn(prefix + " " + msg, t);
    }
}
