package com.github.xiaozhaoz1.dshpet.download;

import com.github.xiaozhaoz1.dshpet.anim.AnimationClip;
import com.github.xiaozhaoz1.dshpet.anim.GifDecoder;
import com.github.xiaozhaoz1.dshpet.config.SharedConfig;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetCatalog;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetDownloader;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetManifest;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetPack;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetStore;
import com.github.xiaozhaoz1.dshpet.core.FrameGeometry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * **真实网络 + 真实素材**的端到端测试（默认启用；网络不可用时自动跳过并打印原因）。
 *
 * <p>为什么必须有这条（2026-09-23 用户质问"你自己测试全测了吗"）：此前所有测试都用**假数据**，
 * 从未验证过"上游真文件能否被本项目解码器解出**正确参数**"。这条测试用**清单里的真 URL**
 * （jsDelivr 等镜像）下载真实 GIF，然后：</p>
 * <ol>
 *   <li>校验 md5 与清单一致（证明镜像内容 = 上游 pinned commit）</li>
 *   <li>用生产解码器 {@link GifDecoder} 解码（证明真文件可解析，不是只在假数据上通过）</li>
 *   <li>断言**真实参数**：帧数 / 画布尺寸 / fps / 内容盒 —— 并打印，供人工核对</li>
 *   <li>走 {@link FrameGeometry#scaleForViewport} 算缩放（390×255 用户环境）并断言落在合理区间</li>
 *   <li>入库后能被 {@link AssetStore} 扫描到、署名可读</li>
 * </ol>
 *
 * <p>只下载**少数几个**文件（默认 2 个，可由 {@code dshpet.test.liveSamples} 调整），
 * 避免测试慢/占用上游带宽。</p>
 */
class LiveAssetsE2ETest {

    /** 采样数（够证明"真文件可解"，又不至于拖慢测试）。 */
    private static int samples() {
        String v = System.getProperty("dshpet.test.liveSamples");
        try {
            return v == null ? 2 : Math.max(1, Math.min(5, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return 2;
        }
    }

    @Test
    @DisplayName("真网络: 从 CDN 下真实 GIF → md5 一致 → 解码真实参数 → 入库可扫描")
    void liveDownloadDecodeAndInstall(@TempDir Path tmp) throws Exception {
        com.github.xiaozhaoz1.dshpet.config.ConfigPaths.init(tmp);

        // ① 内置清单（纯元数据）
        AssetManifest manifest = AssetCatalog.readBundled();
        assumeTrue(manifest != null, "内置清单缺失（跳过）");
        AssetPack def = manifest.defaultPack();
        assertNotNull(def, "内置清单应含 default 包");

        List<AssetPack.FileEntry> entries = def.downloadEntries();
        assertTrue(entries.size() >= samples(), "清单条目不足");

        // ② 取前 N 个条目，各试其候选镜像
        List<AssetPack.FileEntry> picked = entries.subList(0, samples());
        AssetPack sub = new AssetPack("live", "live", def.version(), def.authors(), def.website(),
                def.license(), def.category(), "", 0, "", "", List.copyOf(picked),
                java.util.Map.of("idle", List.of(picked.get(0).name())));

        Path packDir = AssetStore.packDir("live");
        AssetDownloader.Result r = AssetDownloader.download(sub, packDir, new HttpAssetFetch(), null);
        assumeTrue(r.ok(), () -> "网络不可用/镜像全失败，跳过真机验证（原因: " + r.message() + "）");
        System.out.println("[LIVE] 下载成功: " + r.message());

        // ③ 逐个解码并打印真实参数
        for (AssetPack.FileEntry e : picked) {
            Path gif = packDir.resolve("animations").resolve(e.file());
            assertTrue(Files.isRegularFile(gif), () -> "应落盘: " + gif);
            assertEquals(e.fileSize(), Files.size(gif), "落盘大小应等于清单声明");

            AnimationClip clip;
            try (InputStream in = Files.newInputStream(gif)) {
                clip = GifDecoder.decode(in, e.name(), AnimationClip.Source.USER);
            }
            FrameGeometry.Bounds bounds = FrameGeometry.unionBounds(
                    clip.width(), clip.height(), clip.frames());

            // 上游素材规格（本机实测基准）：72×92 画布 · 120 帧 · ~12fps · 内容盒满帧
            System.out.printf("[LIVE] %s: %d 帧 / 画布 %dx%d / %.1f fps / 内容盒 %s%n",
                    e.name(), clip.frameCount(), clip.width(), clip.height(), clip.fps(),
                    bounds == null ? "null" : bounds.width() + "x" + bounds.height()
                            + "@(" + bounds.minX() + "," + bounds.minY() + ")");

            assertTrue(clip.frameCount() >= 2, () -> "应是动图（多帧）: " + clip.frameCount());
            assertTrue(clip.width() >= 32 && clip.height() >= 32,
                    () -> "尺寸应合理: " + clip.width() + "x" + clip.height());
            assertTrue(clip.fps() > 1 && clip.fps() < 60, () -> "fps 应合理: " + clip.fps());
            assertNotNull(bounds, "内容盒不应为 null（有可见像素）");
            assertTrue(bounds.width() > 0 && bounds.height() > 0);

            // ④ 按用户屏幕（390×255 逻辑分辨率）算缩放并断言合理区间
            float scale = FrameGeometry.scaleForViewport(390, 255, bounds.width(), bounds.height(),
                    0.15f, 0.25f, 48f, 320f);
            float drawnH = bounds.height() * scale;
            float drawnW = bounds.width() * scale;
            System.out.printf("[LIVE]   → 缩放 %.3f ⇒ 绘制 %.0fx%.0f px（占屏高 %.0f%%）%n",
                    scale, drawnW, drawnH, drawnH * 100 / 255);
            assertTrue(drawnH <= 255 * 0.25f + 0.5f, "不得超高上限 25%");
            assertTrue(drawnW > 8 && drawnH > 8, "不得小到看不见");
        }

        // ⑤ 入库可扫描 + 署名可读
        var installed = AssetStore.installed();
        assertTrue(installed.stream().anyMatch(p -> "live".equals(p.id())),
                "安装后应能被扫描到");
        AssetPack stored = installed.stream().filter(p -> "live".equals(p.id())).findFirst().orElseThrow().pack();
        assertTrue(stored.attribution().contains("NON-COMMERCIAL"), "署名须含许可口径");
        assertEquals("PC2005-cloud", stored.authorsText());
        System.out.println("[LIVE] 署名: " + stored.attribution());
    }

    @Test
    @DisplayName("真网络: 清单里所有条目的候选 URL 都指向同一文件（防清单生成时拼错）")
    void allCandidateUrlsPointToSameFile() throws Exception {
        AssetManifest manifest = AssetCatalog.readBundled();
        assumeTrue(manifest != null, "内置清单缺失（跳过）");
        for (AssetPack.FileEntry e : manifest.defaultPack().downloadEntries()) {
            for (String u : e.candidateUrls()) {
                assertTrue(u.endsWith(e.file()),
                        () -> "镜像 URL 与文件名不匹配: " + u + " vs " + e.file());
                assertTrue(u.contains("d3988fa52fccaae8249d94ba31e9e4bae073362d"),
                        () -> "必须钉在 pinned commit 上: " + u);
            }
        }
        System.out.println("[LIVE] 清单 " + manifest.defaultPack().downloadEntries().size()
                + " 条 × 多镜像 URL 全部自洽");
    }

    @Test
    @DisplayName("真网络: HTTP 头显示 CDN 可达（对主镜像做一次 HEAD 级验证）")
    void primaryMirrorReachable() throws Exception {
        AssetManifest manifest = AssetCatalog.readBundled();
        assumeTrue(manifest != null, "内置清单缺失（跳过）");
        AssetPack.FileEntry first = manifest.defaultPack().downloadEntries().get(0);
        HttpAssetFetch fetch = new HttpAssetFetch();
        byte[] head;
        try {
            head = fetch.fetch(first.url(), 4096);
        } catch (Throwable t) {
            assumeTrue(false, "主镜像不可用，跳过（" + t + "）");
            return;
        }
        assertTrue(head.length > 0, "应能读到前几个字节");
        String magic = new String(head, 0, Math.min(6, head.length), java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(magic.startsWith("GIF8"), () -> "应是 GIF 内容，实际首字节: " + magic);
        System.out.println("[LIVE] 主镜像可达: " + AssetCatalog.shortUrl(first.url())
                + " · 首 6 字节=" + magic);
    }
}
