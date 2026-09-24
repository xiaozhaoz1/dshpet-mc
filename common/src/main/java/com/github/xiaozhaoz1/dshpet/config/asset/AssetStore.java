package com.github.xiaozhaoz1.dshpet.config.asset;

import com.github.xiaozhaoz1.dshpet.DshPetLog;
import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.config.SharedConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 已下载素材包的本地库（扫描式，无独立索引）—— 零 MC 依赖。
 *
 * <p><b>为什么扫描而不是维护 index.json</b>（2026-09-23 取舍）：包目录里已经有 `pack.json`
 * （下载时原样落盘，含署名/许可）。再维护一份索引 = 两处状态要同步 = 又一处"会漂移的第二份事实"。
 * 目录规模是"个数级"（用户下载的模型包），扫描开销可忽略。</p>
 *
 * <p>目录布局（= 方案 §2）：</p>
 * <pre>
 * config/dshpet/assets/
 *   &lt;packId&gt;/pack.json           ← 元数据（含 author/website/license）
 *   &lt;packId&gt;/animations/*.gif|png  ← 该模型动画
 *   manifest-cache.json            ← 清单缓存（见 AssetCatalog）
 * </pre>
 */
public final class AssetStore {

    private AssetStore() {
    }

    /** 某个包的目录。 */
    public static Path packDir(String packId) {
        return ConfigPaths.assetsDir().resolve(packId);
    }

    /** 某个包的动画目录（**加载顺序第 ② 级**）。 */
    public static Path packAnimationsDir(String packId) {
        return packDir(packId).resolve("animations");
    }

    /** 当前启用包的动画目录；无启用包时返回 null。 */
    public static Path activeAnimationsDir() {
        String id = SharedConfig.activePack();
        if (id == null || id.isBlank()) {
            return null;
        }
        return Files.isDirectory(packAnimationsDir(id)) ? packAnimationsDir(id) : null;
    }

    /**
     * 当前启用包声明的首选动画名（{@code pack.json} 的 {@code animations.idle[0]}）。
     *
     * <p>素材不内嵌后无法写死动画名；空串表示未启用/无声明（调用方回落目录内首个文件）。</p>
     */
    public static String activePreferredAnimation() {
        String id = SharedConfig.activePack();
        if (id == null || id.isBlank()) {
            return "";
        }
        Path meta = packDir(id).resolve("pack.json");
        if (!Files.isRegularFile(meta)) {
            return "";
        }
        try {
            AssetPack pack = AssetPack.of(AssetJson.parseObject(
                    Files.readString(meta, StandardCharsets.UTF_8)));
            return pack.preferredAnimationName();
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.ASSET, "读取启用包的首选动画名失败: {}", t.toString());
            return "";
        }
    }

    /** 该包是否已安装（存在 pack.json）。 */
    public static boolean isInstalled(String packId) {
        return packId != null && Files.isRegularFile(packDir(packId).resolve("pack.json"));
    }

    /**
     * 扫描已安装的包（按目录名排序，保证 UI 顺序稳定）。
     *
     * <p>坏 `pack.json` 跳过（单个坏包不该让列表整体失败）。</p>
     */
    public static List<InstalledPack> installed() {
        List<InstalledPack> out = new ArrayList<>();
        Path root = ConfigPaths.assetsDir();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path meta = dir.resolve("pack.json");
                if (!Files.isRegularFile(meta)) {
                    continue;
                }
                try {
                    AssetPack pack = AssetPack.of(
                            AssetJson.parseObject(Files.readString(meta, StandardCharsets.UTF_8)));
                    out.add(new InstalledPack(pack, dir, countAnimations(dir.resolve("animations"))));
                } catch (Throwable t) {
                    DshPetLog.warn(DshPetLog.ASSET, "包元数据损坏，已跳过: {}（{}）",
                            dir.getFileName(), t.toString());
                }
            }
        } catch (IOException e) {
            DshPetLog.warn(DshPetLog.ASSET, "扫描素材目录失败: {}", e.toString());
        }
        return out;
    }

    /** 已安装包（元数据 + 目录 + 动画文件数）。 */
    public record InstalledPack(AssetPack pack, Path dir, int animationCount) {
        public String id() {
            return pack.id();
        }
    }

    /**
     * 删除一个已安装的包。
     *
     * @return 是否删掉（不存在时返回 false）
     */
    public static boolean remove(String packId) {
        if (packId == null || packId.isBlank() || !isInstalled(packId)) {
            return false;
        }
        Path dir = packDir(packId);
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力而为
                }
            });
        } catch (IOException e) {
            DshPetLog.warn(DshPetLog.ASSET, "删除包失败: {}（{}）", packId, e.toString());
            return false;
        }
        if (packId.equals(SharedConfig.activePack())) {
            SharedConfig.setActivePack(""); // 启用包被删 ⇒ 清空，回落用户目录/内置
        }
        return true;
    }

    /** 该包是否可更新（清单 version 与本地 version 不同）。 */
    public static boolean needsUpdate(AssetPack remote, InstalledPack local) {
        if (remote == null || local == null) {
            return false;
        }
        return !normalize(remote.version()).equals(normalize(local.pack().version()));
    }

    private static String normalize(String version) {
        return version == null ? "" : version.trim().toLowerCase(Locale.ROOT);
    }

    private static int countAnimations(Path animDir) {
        if (!Files.isDirectory(animDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(animDir)) {
            return (int) files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".gif") || n.endsWith(".png");
                    })
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }
}
