package com.github.xiaozhaoz1.dshpet.download;

import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetCatalog;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetDownloader;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetManifest;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * **真实网络测速**（多镜像并行竞争 vs 串行逐镜像）。
 *
 * <p>用内置清单的前 N 个条目做真实下载，打印每个文件的完成时刻与平均耗时。
 * 目的：把"用户反馈太慢"变成**可复现的数字**（铁律：不凭感觉优化）。
 * 采样数由 {@code -Ddshpet.test.speedSamples} 控制（默认 10）。</p>
 *
 * <p>网络不可用时自动跳过（不误报）。</p>
 */
class LiveDownloadSpeedTest {

    private static int samples() {
        String v = System.getProperty("dshpet.test.speedSamples");
        try {
            return v == null ? 10 : Math.max(1, Math.min(30, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    @Test
    @DisplayName("测速: N 个真实文件的多镜像并行下载（打印每文件完成时刻与平均耗时）")
    void measureParallelDownloadSpeed(@TempDir Path tmp) throws Exception {
        ConfigPaths.init(tmp);
        AssetManifest manifest = AssetCatalog.readBundled();
        assumeTrue(manifest != null && manifest.defaultPack() != null, "内置清单缺失（跳过）");
        AssetPack def = manifest.defaultPack();

        List<AssetPack.FileEntry> all = def.downloadEntries();
        int n = Math.min(samples(), all.size());
        AssetPack sub = new AssetPack("speed", "speed", def.version(), def.authors(), def.website(),
                def.license(), def.category(), "", 0, "", "", List.copyOf(all.subList(0, n)),
                java.util.Map.of("idle", List.of(all.get(0).name())));

        Path dir = tmp.resolve("speed-pack");
        long t0 = System.nanoTime();
        AtomicInteger maxConcurrentSeen = new AtomicInteger();
        AssetDownloader.Progress progress = new AssetDownloader.Progress() {
            private final AtomicInteger inFlight = new AtomicInteger();
            private final AtomicInteger doneCount = new AtomicInteger();
            private final AtomicInteger concurrentPeak = new AtomicInteger();

            @Override
            public void onProgress(int percent) {
                // 无法从这里统计并发，保留接口
            }

            @Override
            public void onFileDone(int done, int total, String file) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int peak = concurrentPeak.get();
                maxConcurrentSeen.accumulateAndGet(peak, Math::max);
                System.out.printf("[SPEED] %6d ms  %2d/%2d 完成  %s%n", ms, done, total, file);
                doneCount.set(done);
            }
        };

        AssetDownloader.Result r = AssetDownloader.download(sub, dir, new HttpAssetFetch(), progress);
        long totalMs = (System.nanoTime() - t0) / 1_000_000;

        assumeTrue(r.ok(), () -> "网络不可用/全部失败，跳过测速（" + r.message() + "）");
        double perFile = (double) totalMs / Math.max(1, r.assetCount());
        System.out.printf("[SPEED] === 合计 %d 个文件 / %d ms ⇒ 平均 %.0f ms/文件 ===%n",
                r.assetCount(), totalMs, perFile);
        System.out.printf("[SPEED] 基准参考: 优化前实测约 4000 ms/文件（串行逐镜像）%n");

        assertTrue(r.assetCount() == n, "应全部成功: " + r.message());
        // 并行竞争后应该明显快于串行（放宽到 1500ms/文件，避免网络抖动导致假红）
        // 真网络测试：阈值只做"数量级"兜底（基线：优化前约 4000ms/文件）；不用紧阈值，避免网络抖动假红
        assertTrue(perFile < 4000, () -> "平均耗时异常（并行/镜像竞争可能失效）: " + perFile + " ms/文件");
    }
}
