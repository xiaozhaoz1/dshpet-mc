package com.github.xiaozhaoz1.dshpet.config.asset;

import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssetStore} 单测 —— 纯 JVM（用 {@link TempDir} 隔离，绝不碰真实 config 目录）。
 *
 * <p>覆盖：安装检测、扫描（坏 pack.json 跳过）、删除、目录布局、更新判定、id 路径安全。</p>
 */
class AssetStoreTest {

    private static void useTempRoot(Path tmp) {
        ConfigPaths.init(tmp);
    }

    private static void writePack(Path root, String id, String version, int animCount) throws Exception {
        Path dir = root.resolve("dshpet").resolve("assets").resolve(id);
        Files.createDirectories(dir.resolve("animations"));
        Files.writeString(dir.resolve("pack.json"), """
                {
                  "id": "%s", "name": "n", "version": "%s",
                  "author": ["PC2005-cloud"], "website": "https://github.com/PC2005-cloud/dsh-pet",
                  "license": "open-source use, NON-COMMERCIAL", "category": "model",
                  "fileName": "x.zip", "fileSize": 1, "checksum": "", "url": "https://x/y.zip"
                }
                """.formatted(id, version), StandardCharsets.UTF_8);
        for (int i = 0; i < animCount; i++) {
            Files.write(dir.resolve("animations").resolve("a" + i + ".gif"),
                    "GIF89a".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("目录布局: packDir / packAnimationsDir 落在 config/dshpet/assets/<id>/")
    void layout(@TempDir Path tmp) {
        useTempRoot(tmp);
        Path expected = tmp.resolve("dshpet/assets/default");
        assertEquals(expected, AssetStore.packDir("default"));
        assertEquals(expected.resolve("animations"), AssetStore.packAnimationsDir("default"));
    }

    @Test
    @DisplayName("扫描: 只认有 pack.json 的目录；坏元数据跳过；统计动画数")
    void scanInstalled(@TempDir Path tmp) throws Exception {
        useTempRoot(tmp);
        writePack(tmp, "alpha", "1.0", 3);
        writePack(tmp, "beta", "2.0", 1);
        // 坏包：pack.json 是垃圾
        Path broken = tmp.resolve("dshpet/assets/broken");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("pack.json"), "{ not json", StandardCharsets.UTF_8);
        // 非包目录（无 pack.json）
        Files.createDirectories(tmp.resolve("dshpet/assets/not-a-pack"));

        List<AssetStore.InstalledPack> packs = AssetStore.installed();
        assertEquals(2, packs.size(), "只应有 alpha/beta 两个有效包（broken 跳过、not-a-pack 不算）");
        assertEquals("alpha", packs.get(0).id(), "应按目录名排序（顺序稳定）");
        assertEquals("beta", packs.get(1).id());
        assertEquals(3, packs.get(0).animationCount());
        assertEquals(1, packs.get(1).animationCount());
    }

    @Test
    @DisplayName("安装检测: isInstalled 只认 pack.json 存在")
    void isInstalledCheck(@TempDir Path tmp) throws Exception {
        useTempRoot(tmp);
        writePack(tmp, "alpha", "1.0", 1);
        assertTrue(AssetStore.isInstalled("alpha"));
        assertFalse(AssetStore.isInstalled("nope"));
        assertFalse(AssetStore.isInstalled(null));
        assertFalse(AssetStore.isInstalled(""), "空 id 不算已安装");
    }

    @Test
    @DisplayName("删除: 连目录带内容删除；不存在时返回 false（不抛）")
    void removePack(@TempDir Path tmp) throws Exception {
        useTempRoot(tmp);
        writePack(tmp, "alpha", "1.0", 2);
        assertTrue(AssetStore.remove("alpha"));
        assertFalse(Files.exists(tmp.resolve("dshpet/assets/alpha")));
        assertFalse(AssetStore.remove("alpha"), "已删除 ⇒ 再删返回 false");
        assertFalse(AssetStore.remove("never"), "不存在的包 ⇒ false");
    }

    @Test
    @DisplayName("更新判定: 版本不同 ⇒ NEED_UPDATE 语义为 true；相同 ⇒ false")
    void needsUpdate(@TempDir Path tmp) throws Exception {
        useTempRoot(tmp);
        writePack(tmp, "alpha", "1.0", 1);
        AssetStore.InstalledPack local = AssetStore.installed().get(0);

        AssetPack sameVersion = new AssetPack("alpha", "n", "1.0", List.of("a"), "", "lic", "model",
                "x.zip", 1, "", "https://x/y.zip", List.of(), java.util.Map.of());
        assertFalse(AssetStore.needsUpdate(sameVersion, local), "版本相同不应提示更新");

        AssetPack newer = new AssetPack("alpha", "n", "2.0", List.of("a"), "", "lic", "model",
                "x.zip", 1, "", "https://x/y.zip", List.of(), java.util.Map.of());
        assertTrue(AssetStore.needsUpdate(newer, local), "版本不同应提示更新（NEED_UPDATE）");

        // 大小写/空格差异不算更新
        AssetPack sameLoose = new AssetPack("alpha", "n", " 1.0 ", List.of("a"), "", "lic", "model",
                "x.zip", 1, "", "https://x/y.zip", List.of(), java.util.Map.of());
        assertFalse(AssetStore.needsUpdate(sameLoose, local), "仅空白/大小写差异不应算更新");

        assertFalse(AssetStore.needsUpdate(null, local), "null 安全");
        assertFalse(AssetStore.needsUpdate(newer, null), "null 安全");
    }

    @Test
    @DisplayName("当前启用包: 目录不存在时 activeAnimationsDir 返回 null（不抛）")
    void activeDirIsNullWhenMissing(@TempDir Path tmp) {
        useTempRoot(tmp);
        // 未配置启用包（默认空）⇒ null；绝不抛异常（渲染热路径）
        assertNull(AssetStore.activeAnimationsDir());
    }
}
