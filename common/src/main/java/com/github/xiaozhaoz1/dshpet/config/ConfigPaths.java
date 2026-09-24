package com.github.xiaozhaoz1.dshpet.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 路径与配置目录 —— 唯一入口（避免各处各自拼路径导致分裂）。
 *
 * <p>目录约定（docs/PLAN.md §1/§2）：</p>
 * <pre>
 * .minecraft/config/dshpet/animations/*.gif   用户动画（优先级高于 jar 内置）
 * .minecraft/config/dshpet/overrides.json     动画配置覆盖（M5）
 * .minecraft/config/dshpet/config.json        实例配置（M2）
 * </pre>
 */
public final class ConfigPaths {

    private static Path root;

    private ConfigPaths() {
    }

    /** 注入配置根目录（由平台入口调用：{@code FabricLoader/FMLPaths.CONFIGDIR} 等）。 */
    public static void init(Path configDir) {
        root = configDir.resolve("dshpet");
    }

    public static Path root() {
        if (root == null) {
            throw new IllegalStateException("ConfigPaths.init(...) 尚未调用（应由平台入口在启动期注入）");
        }
        return root;
    }

    /** 用户动画目录；不存在时按需创建（失败也不抛 —— 只读场景下不应因此崩）。 */
    public static Path animationsDir() {
        Path p = root().resolve("animations");
        try {
            Files.createDirectories(p);
        } catch (Throwable ignored) {
            // 目录创建失败不致命: 后续读取会自然失败并走"回退内置"路径
        }
        return p;
    }

    /**
     * 已下载素材包根目录（`config/dshpet/assets/`）—— **加载顺序第 ② 级**的根。
     *
     * <p>目录布局见 {@code config/asset/AssetStore}：{@code <packId>/pack.json + animations/}。</p>
     */
    public static Path assetsDir() {
        Path p = root().resolve("assets");
        try {
            Files.createDirectories(p);
        } catch (Throwable ignored) {
            // 同 animationsDir: 创建失败不致命
        }
        return p;
    }

    /** 清单缓存文件（离线可用的清单副本）。 */
    public static Path manifestCacheFile() {
        return assetsDir().resolve("manifest-cache.json");
    }
}
