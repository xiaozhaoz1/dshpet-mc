package com.github.xiaozhaoz1.dshpet.config.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **内置清单**（{@code assets-index.json}，随 jar 打包）的契约测试。
 *
 * <p>为什么必须测：清单是"素材下载"的唯一入口数据 —— 它一旦损坏/结构漂移，用户界面会空白且
 * 无从排查（而我看不见画面）。这里直接校验**真实内置资源**的内容契约。</p>
 *
 * <p>合规要点（一并守护）：清单**只含元数据**（文件名/大小/md5/上游直链），
 * <b>不含素材本体</b>，且每个条目都指向**上游 pinned commit**，本项目不分发素材。</p>
 */
class BundledManifestContractTest {

    private static final String RESOURCE = "/assets/dshpet/assets-index.json";

    private static AssetManifest load() throws Exception {
        try (InputStream in = AssetCatalog.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(in, () -> "内置清单缺失: " + RESOURCE);
            return AssetManifest.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("契约: 内置清单可解析，且含 id=default 的包与 106 个文件条目")
    void bundledManifestIsUsable() throws Exception {
        AssetManifest manifest = load();
        assertEquals(AssetManifest.CURRENT_VERSION, manifest.manifestVersion());
        assertEquals("0.1.0", manifest.minModVersion());

        AssetPack def = manifest.defaultPack();
        assertNotNull(def, "必须含 id=default 的包（/dshpet assets default 依赖它）");
        assertEquals("PC2005-cloud", def.authorsText());
        assertEquals("open-source use, NON-COMMERCIAL", def.license());
        assertTrue(def.website().contains("PC2005-cloud/dsh-pet"), "署名须含上游地址");

        List<AssetPack.FileEntry> entries = def.downloadEntries();
        assertEquals(106, entries.size(), "上游 preview 目录共 106 个 GIF（GitHub API 实证）");
        assertTrue(def.downloadable(), "包必须可下载");
    }

    @Test
    @DisplayName("合规: 条目指向**上游 pinned commit**（主 CDN + 多镜像兜底），带 md5/大小（本项目不分发素材）")
    void everyEntryPointsUpstream() throws Exception {
        AssetPack def = load().defaultPack();
        assertNotNull(def);
        String commit = "d3988fa52fccaae8249d94ba31e9e4bae073362d";
        String repo = "PC2005-cloud/dsh-pet";
        for (AssetPack.FileEntry e : def.downloadEntries()) {
            assertTrue(e.url().startsWith("https://"), () -> "主 URL 必须是 https: " + e.url());
            assertTrue(e.url().contains(repo) && e.url().contains(commit),
                    () -> "主 URL 必须指向该仓库的 pinned commit: " + e.url());
            assertTrue(e.file().endsWith(".gif"), () -> "必须是 .gif: " + e.file());
            assertTrue(e.fileSize() > 0, () -> "必须声明大小: " + e.file());
            assertTrue(e.checksum().matches("[a-f0-9]{32}"), () -> "必须是 md5: " + e.checksum());
            assertFalse(e.name().isBlank(), "条目须有可用作动画名的 name");

            // 镜像兜底：至少 1 个备用 URL，且都指向同一文件
            List<String> candidates = e.candidateUrls();
            assertTrue(candidates.size() >= 2, () -> "应有镜像兜底: " + e.file());
            for (String u : candidates) {
                assertTrue(u.endsWith(e.file()), () -> "镜像必须指向同一文件: " + u);
            }
        }
    }

    @Test
    @DisplayName("可用性: 主 URL 应为国内可达的 CDN（jsDelivr），且 raw 仅作兜底")
    void primaryUrlIsCdnForChina() throws Exception {
        AssetPack def = load().defaultPack();
        assertNotNull(def);
        for (AssetPack.FileEntry e : def.downloadEntries()) {
            assertTrue(e.url().contains("jsdelivr.net"),
                    () -> "主 URL 应为 jsDelivr（国内可达）: " + e.url());
            List<String> alt = e.altUrls();
            assertTrue(alt.stream().anyMatch(u -> u.contains("raw.githubusercontent.com")),
                    () -> "raw 应作为兜底之一: " + alt);
            assertTrue(alt.stream().anyMatch(u -> u.contains("jsdelivr.net")),
                    () -> "应有第二个 jsDelivr 镜像（fastly/gcore）: " + alt);
        }
    }

    @Test
    @DisplayName("契约: 首选动画名来自清单声明（渲染侧据此加载，不写死动画名）")
    void preferredAnimationComesFromManifest() throws Exception {
        AssetPack def = load().defaultPack();
        assertNotNull(def);
        assertEquals("dongzhangxiwang", def.preferredAnimationName(),
                "idle 声明应指向 dongzhangxiwang（与本地留存素材 md5 一致的那个）");
    }

    @Test
    @DisplayName("契约: 内置清单不含素材本体（只有元数据 —— 合规硬要求）")
    void bundledManifestHasNoAssetPayload() throws Exception {
        try (InputStream in = AssetCatalog.class.getResourceAsStream(RESOURCE)) {
            byte[] raw = in.readAllBytes();
            String text = new String(raw, StandardCharsets.UTF_8);
            assertFalse(text.contains("GIF89a"), "清单里不得出现 GIF 头（素材本体不得内嵌）");
            assertFalse(text.contains("PNG\u0000"), "清单里不得出现 PNG 头");
            // 单条 url 指向远端 ⇒ 表明是元数据而非内容
            assertTrue(text.contains("\"url\": \"https://"), "条目应指向远端 URL");
        }
    }

    @Test
    @DisplayName("契约: 条目 md5 与本地留存的同源文件一致（跨来源交叉验证）")
    void checksumMatchesKnownGoodFile() throws Exception {
        AssetPack def = load().defaultPack();
        assertNotNull(def);
        // 这两个 md5 由本机对上游文件逐字节计算得出（见 docs/AUDIT-standards-2026-09-23.md）
        for (AssetPack.FileEntry e : def.downloadEntries()) {
            if ("dongzhangxiwang".equals(e.name())) {
                assertEquals("d4fb09760767a2ed499f12ffea1b1458", e.checksum(),
                        "dongzhangxiwang 的 md5 应与已知良品一致");
            }
            if ("keai-zhaiwu".equals(e.name())) {
                assertEquals("f077d73466ddb766e6a3d3da43be477b", e.checksum(),
                        "keai-zhaiwu 的 md5 应与已知良品一致");
            }
        }
    }
}
