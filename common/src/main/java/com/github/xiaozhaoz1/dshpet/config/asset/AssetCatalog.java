package com.github.xiaozhaoz1.dshpet.config.asset;

import com.github.xiaozhaoz1.dshpet.DshPetLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 素材清单获取（`index.json`）—— 纯逻辑，零 MC 依赖。
 *
 * <p>获取顺序**照 TLM 实证设计**（`InfoGetManager`：`ROOT_URL` 失败切 `ROOT_URL_BACKUP`；断网时用本地缓存）：</p>
 * <ol>
 *   <li>主 URL（{@code dshpet.assets.index.url} 可覆盖，便于自测/自托管）</li>
 *   <li>备用 URL（同理 {@code dshpet.assets.index.url.backup}）</li>
 *   <li>本地缓存 `manifest-cache.json`（离线可用；比"完全没有清单"强）</li>
 * </ol>
 *
 * <p>全部失败 ⇒ 返回 {@link AssetManifest#empty()}，**不抛异常**（下载功能降级而非崩游戏）。</p>
 */
public final class AssetCatalog {

    /** 主清单 URL（可通过系统属性覆盖；默认指向项目 GitHub Release 附件）。 */
    public static final String PROP_PRIMARY = "dshpet.assets.index.url";
    /** 备用清单 URL。 */
    public static final String PROP_BACKUP = "dshpet.assets.index.url.backup";

    /** 清单允许的最大字节数（清单很小；超限=异常响应）。 */
    private static final long MAX_MANIFEST_BYTES = 512L * 1024L;

    private AssetCatalog() {
    }

    /** 主 URL（系统属性优先，便于用户/自测覆盖）。 */
    public static String primaryUrl() {
        return System.getProperty(PROP_PRIMARY, defaultPrimaryUrl());
    }

    /** 备用 URL（系统属性优先）。 */
    public static String backupUrl() {
        return System.getProperty(PROP_BACKUP, defaultBackupUrl());
    }

    /** 默认主清单地址（GitHub Release 附件；发布前替换为真实地址）。 */
    private static String defaultPrimaryUrl() {
        return "https://github.com/xiaozhaoz1/dshpet-mc/releases/latest/download/index.json";
    }

    /** 默认备用清单地址（另一托管点；留空表示无备用）。 */
    private static String defaultBackupUrl() {
        return "";
    }

    /**
     * 取清单：主 → 备 → 本地缓存 → 空。
     *
     * @param fetch        远端获取（真实/S测试注入）
     * @param cacheFile    本地缓存路径（可为 null 表示不缓存）
     * @param localVersion 本 mod 版本（用于 minModVersion 判断与日志）
     */
    public static AssetManifest load(AssetFetch fetch, Path cacheFile, String localVersion) {
        return load(fetch, cacheFile, localVersion, true);
    }

    /**
     * 取清单（可关闭内置回落 —— 供测试构造"完全无清单"的场景）。
     *
     * @param allowBundled false 时不使用 jar 内置清单
     */
    public static AssetManifest load(AssetFetch fetch, Path cacheFile, String localVersion,
                                     boolean allowBundled) {
        for (String url : new String[] {primaryUrl(), backupUrl()}) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                String text = fetch.fetchText(url, MAX_MANIFEST_BYTES);
                AssetManifest manifest = AssetManifest.parse(text);
                if (!manifest.supported()) {
                    DshPetLog.warn(DshPetLog.ASSET,
                            "清单格式版本 {} 高于本 mod 支持的 {} ⇒ 已忽略（请升级 mod）",
                            manifest.manifestVersion(), AssetManifest.CURRENT_VERSION);
                    return AssetManifest.empty();
                }
                DshPetLog.info(DshPetLog.ASSET, "清单已获取: {} （来源 {}）", manifest.summary(), shortUrl(url));
                writeCache(cacheFile, text);
                return manifest;
            } catch (Throwable t) {
                DshPetLog.warn(DshPetLog.ASSET, "清单获取失败（{}）: {}", shortUrl(url), t.toString());
            }
        }
        AssetManifest cached = readCache(cacheFile);
        if (cached != null) {
            DshPetLog.info(DshPetLog.ASSET, "使用本地缓存清单: {}", cached.summary());
            return cached;
        }
        AssetManifest bundled = allowBundled ? readBundled() : null;
        if (bundled != null) {
            DshPetLog.info(DshPetLog.ASSET, "使用内置清单（随 mod 打包）: {}", bundled.summary());
            return bundled;
        }
        DshPetLog.warn(DshPetLog.ASSET, "无可用清单（联网失败且无缓存/内置）⇒ 下载功能暂不可用");
        return AssetManifest.empty();
    }

    // ── 本地缓存（原子写；坏缓存不影响启动） ─────────────────────────────────

    /** 缓存文件名（放素材根目录）。 */
    public static final String CACHE_FILE_NAME = "manifest-cache.json";

    /** 内置清单资源路径（随 jar 打包 —— 只含元数据：文件名/大小/md5/上游直链，**不含素材本体**）。 */
    public static final String BUNDLED_RESOURCE = "/assets/dshpet/assets-index.json";

    /**
     * 读内置清单（jar 资源）。
     *
     * <p>为什么内置（2026-09-23 裁定）：素材本体受上游"禁止商用"限制、<b>不由本项目分发</b>；
     * 但**清单只是元数据**（文件名/大小/md5/直链），随 jar 分发既合规又让用户在第一次就能
     * 看到可下载列表，无需依赖某个内部托管点。</p>
     */
    public static AssetManifest readBundled() {
        try (java.io.InputStream in = AssetCatalog.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return null;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return AssetManifest.parse(text);
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.ASSET, "内置清单读取失败（忽略）: {}", t.toString());
            return null;
        }
    }

    private static void writeCache(Path cacheFile, String text) {
        if (cacheFile == null) {
            return;
        }
        try {
            Path dir = cacheFile.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.ASSET, "清单缓存写入失败（不影响本次使用）: {}", t.toString());
        }
    }

    private static AssetManifest readCache(Path cacheFile) {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            return AssetManifest.parse(Files.readString(cacheFile, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.ASSET, "本地缓存清单损坏，已忽略: {}", t.toString());
            return null;
        }
    }

    /** 日志里只显示 host+文件名，避免刷屏长 URL。 */
    public static String shortUrl(String url) {
        if (url == null) {
            return "null";
        }
        int idx = url.lastIndexOf('/');
        String tail = idx >= 0 && idx < url.length() - 1 ? url.substring(idx + 1) : url;
        String host = "";
        int scheme = url.indexOf("://");
        if (scheme >= 0) {
            int end = url.indexOf('/', scheme + 3);
            host = end > 0 ? url.substring(scheme + 3, end) : url.substring(scheme + 3);
        }
        return host + "/" + tail;
    }
}
