package com.github.xiaozhaoz1.dshpet.config.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssetDownloader} 单测 —— 用**假 fetch**注入，无需联网。
 *
 * <p>覆盖：成功安装（zip / 单文件）、md5 校验失败、超限、类型不识别、zip-slip 防护、
 * 空包、无 url、临时目录不残留。</p>
 */
class AssetDownloaderTest {

    private static final byte[] FAKE_GIF = "GIF89a-fake-content-0123456789".getBytes(StandardCharsets.UTF_8);

    private static AssetPack packFor(String fileName, byte[] payload, boolean withChecksum) {
        // 条目 file 用真实文件名（多文件包与单文件包语义一致）
        return new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                fileName, payload.length,
                withChecksum ? md5(payload) : "",
                "https://example.com/" + fileName, List.of(), Map.of());
    }

    private static AssetFetch serving(byte[] payload) {
        return (url, max) -> payload;
    }

    @Test
    @DisplayName("成功: zip 包解出多个动画 + 写入 pack.json")
    void installZipPack(@TempDir Path tmp) throws Exception {
        byte[] zip = zipOf(Map.of(
                "animations/dongzhangxiwang.gif", FAKE_GIF,
                "animations/keai-zhaiwu.gif", FAKE_GIF,
                "readme.txt", "ignored".getBytes(StandardCharsets.UTF_8)));
        AssetPack pack = packFor("default.zip", zip, true);

        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("default"), serving(zip), null);
        assertTrue(r.ok(), () -> "应成功: " + r.message());
        assertEquals(DownloadStatus.DOWNLOADED, r.status());
        assertEquals(2, r.assetCount(), "只应解出 2 个 .gif（txt 被忽略）");

        Path dir = tmp.resolve("default");
        assertTrue(Files.isRegularFile(dir.resolve("animations/dongzhangxiwang.gif")));
        assertTrue(Files.isRegularFile(dir.resolve("animations/keai-zhaiwu.gif")));
        assertTrue(Files.isRegularFile(dir.resolve("pack.json")), "署名/许可须随包落盘");
        assertFalse(Files.exists(dir.resolve("readme.txt")), "非白名单扩展名不入库");
    }

    @Test
    @DisplayName("成功: 单个 gif 包（非 zip）")
    void installSingleGif(@TempDir Path tmp) {
        AssetPack pack = packFor("dongzhangxiwang.gif", FAKE_GIF, true);
        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("p"), serving(FAKE_GIF), null);
        assertTrue(r.ok(), () -> "应成功: " + r.message());
        assertEquals(1, r.assetCount());
        assertTrue(Files.isRegularFile(tmp.resolve("p/animations/dongzhangxiwang.gif")));
    }

    @Test
    @DisplayName("失败: md5 不匹配 ⇒ 拒收，且不落盘")
    void checksumMismatchRejects(@TempDir Path tmp) {
        byte[] payload = FAKE_GIF;
        AssetPack wrong = new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                "x.gif", payload.length, "deadbeefdeadbeefdeadbeefdeadbeef",
                "https://example.com/x.gif", List.of(), Map.of());
        AssetDownloader.Result r = AssetDownloader.download(wrong, tmp.resolve("p"), serving(payload), null);
        assertFalse(r.ok());
        assertEquals(DownloadStatus.FAILED, r.status());
        assertTrue(r.message().contains("校验失败"), () -> "消息应说明校验失败: " + r.message());
        assertFalse(hasAnyAnimation(tmp.resolve("p")), "校验失败不得有动画落地");
    }

    @Test
    @DisplayName("失败: 声明体积超上限 ⇒ 下载前即拒")
    void oversizedRejectedBeforeDownload(@TempDir Path tmp) {
        AssetPack huge = new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                "x.zip", AssetDownloader.HARD_MAX_BYTES + 1, "", "https://example.com/x.zip",
                List.of(), Map.of());
        boolean[] fetched = {false};
        AssetFetch spy = (url, max) -> {
            fetched[0] = true;
            return new byte[0];
        };
        AssetDownloader.Result r = AssetDownloader.download(huge, tmp.resolve("p"), spy, null);
        assertFalse(r.ok());
        assertTrue(r.message().contains("超过上限"), () -> r.message());
        assertFalse(fetched[0], "超限包不应发起网络请求（省流量）");
    }

    @Test
    @DisplayName("失败: 类型不识别（既非 zip 也非 gif/png）⇒ 拒收")
    void unknownTypeRejected(@TempDir Path tmp) {
        byte[] junk = "not-an-image-at-all".getBytes(StandardCharsets.UTF_8);
        AssetPack pack = packFor("x.gif", junk, true);
        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("p"), serving(junk), null);
        assertFalse(r.ok());
        assertTrue(r.message().contains("不识别") || r.message().contains("下载或校验失败"), () -> r.message());
    }

    @Test
    @DisplayName("失败: 无 url / 空响应 / fetch 抛异常 ⇒ 稳定的可展示消息")
    void failureMessages(@TempDir Path tmp) {
        AssetPack noUrl = new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                "x.gif", 0, "", "", List.of(), Map.of());
        assertFalse(AssetDownloader.download(noUrl, tmp.resolve("a"), serving(FAKE_GIF), null).ok());

        AssetPack ok = packFor("x.gif", FAKE_GIF, false);
        assertFalse(AssetDownloader.download(ok, tmp.resolve("b"), (u, m) -> new byte[0], null).ok());

        AssetFetch boom = (u, m) -> {
            throw new java.io.IOException("boom");
        };
        AssetDownloader.Result r = AssetDownloader.download(ok, tmp.resolve("c"), boom, null);
        assertFalse(r.ok());
        assertTrue(r.message().contains("下载失败"), () -> r.message());
        assertFalse(hasAnyAnimation(tmp.resolve("c")), "失败不得有动画落地");
    }

    @Test
    @DisplayName("安全: zip-slip 与目录穿越被扁平化丢弃（不写出目标目录）")
    void zipSlipIsNeutralised(@TempDir Path tmp) throws Exception {
        byte[] zip = zipOf(Map.of(
                "../../evil.gif", FAKE_GIF,          // 穿越
                "..\\..\\evil2.gif", FAKE_GIF,       // Windows 风格穿越
                "/abs/path/evil3.gif", FAKE_GIF,     // 绝对路径
                "animations/ok.gif", FAKE_GIF));
        AssetPack pack = packFor("p.zip", zip, false);
        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("p"), serving(zip), null);
        assertTrue(r.ok(), () -> r.message());

        Path animDir = tmp.resolve("p/animations");
        // 穿越条目被扁平化到 animations/ 内（变成普通文件名），绝不逃出目标目录
        assertTrue(Files.isRegularFile(animDir.resolve("evil.gif")), "穿越条目应被扁平化到目标内");
        assertTrue(Files.isRegularFile(animDir.resolve("evil2.gif")));
        assertTrue(Files.isRegularFile(animDir.resolve("evil3.gif")));
        assertTrue(Files.isRegularFile(animDir.resolve("ok.gif")));
        assertFalse(Files.exists(tmp.resolve("evil.gif")), "不得写到目标目录之外");
        assertFalse(Files.exists(tmp.getParent().resolve("evil.gif")));
    }

    @Test
    @DisplayName("失败: 空 zip（无可用动画）⇒ 报错且不留安装目录")
    void emptyPackRejected(@TempDir Path tmp) throws Exception {
        byte[] zip = zipOf(Map.of("readme.txt", "x".getBytes(StandardCharsets.UTF_8)));
        AssetPack pack = packFor("empty.zip", zip, true);
        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("p"), serving(zip), null);
        assertFalse(r.ok(), "空包应判失败（没有可用动画）");
        assertTrue(r.message().contains("没有可用的动画文件"),
                () -> "失败消息应具体说明原因（用户可见），实际: " + r.message());
        assertFalse(hasAnyAnimation(tmp.resolve("p")), "空包不得留下动画文件");
    }

    @Test
    @DisplayName("更新: 重复下载覆盖旧内容（不残留上一个包的动画）")
    void reinstallReplacesOldContent(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("p");
        byte[] first = zipOf(Map.of("old.gif", FAKE_GIF));
        assertTrue(AssetDownloader.download(packFor("p.zip", first, true), dir, serving(first), null).ok());
        assertTrue(Files.isRegularFile(dir.resolve("animations/old.gif")));

        byte[] second = zipOf(Map.of("new.gif", FAKE_GIF));
        assertTrue(AssetDownloader.download(packFor("p.zip", second, true), dir, serving(second), null).ok());
        assertTrue(Files.isRegularFile(dir.resolve("animations/new.gif")));
        assertFalse(Files.exists(dir.resolve("animations/old.gif")),
                "旧动画应被整体替换（下载前已清空）");
    }

    @Test
    @DisplayName("进度: onProgress 报到 100 且 onFileDone 逐文件回调；回调异常不影响结果")
    void progressIsReported(@TempDir Path tmp) {
        AssetPack pack = packFor("x.gif", FAKE_GIF, true);
        java.util.List<Integer> percents = new java.util.ArrayList<>();
        java.util.List<String> filesDone = new java.util.ArrayList<>();
        AssetDownloader.Progress progress = new AssetDownloader.Progress() {
            @Override
            public void onProgress(int percent) {
                percents.add(percent);
            }

            @Override
            public void onFileDone(int done, int total, String file) {
                filesDone.add(done + "/" + total + ":" + file);
            }
        };
        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("a"),
                serving(FAKE_GIF), progress);
        assertTrue(r.ok(), () -> "应成功: " + r.message());
        assertEquals(0, percents.get(0), "首个进度应为 0（用 @TempDir 隔离，避免续传导致跳过）");
        assertEquals(100, percents.get(percents.size() - 1), "成功路径必须报到 100");
        assertEquals(1, filesDone.size(), "单文件包应触发 1 次 onFileDone");
        assertTrue(filesDone.get(0).startsWith("1/1:"), () -> filesDone.get(0));

        // 回调抛异常不得影响下载
        AssetDownloader.Result r2 = AssetDownloader.download(pack, tmp.resolve("b"),
                serving(FAKE_GIF), new AssetDownloader.Progress() {
                    @Override
                    public void onProgress(int percent) {
                        throw new IllegalStateException("callback boom");
                    }

                    @Override
                    public void onFileDone(int done, int total, String file) {
                        throw new IllegalStateException("callback boom");
                    }
                });
        assertTrue(r2.ok(), "进度回调抛异常不得影响下载");
    }

    @Test
    @DisplayName("多镜像竞争: 主镜像慢/失败 ⇒ 备用镜像胜出，且只落一份文件")
    void mirrorRaceWinsWithFastestGood(@TempDir Path tmp) throws Exception {
        byte[] payload = FAKE_GIF;
        String md5 = md5(payload);
        // 主镜像是坏的（抛异常），备用镜像可用 ⇒ 竞争结果必须是备用胜出
        AssetFetch racing = (url, max) -> {
            if (url.contains("primary")) {
                throw new java.io.IOException("primary down");
            }
            return payload;
        };
        AssetPack pack = new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                "", 0, "", "", List.of(new AssetPack.FileEntry("pet", "pet.gif", payload.length, md5,
                        "https://example.com/primary/pet.gif",
                        List.of("https://example.com/backup/pet.gif"))),
                Map.of());

        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("p"), racing, null);
        assertTrue(r.ok(), () -> "备用镜像应胜出: " + r.message());
        Path out = tmp.resolve("p/animations/pet.gif");
        assertTrue(Files.isRegularFile(out), "应落盘");
        assertEquals(payload.length, Files.size(out), "内容应为备用镜像的字节");
        // 并发下不应残留 .part 临时文件
        try (var list = Files.list(tmp.resolve("p/animations"))) {
            assertTrue(list.noneMatch(p -> p.toString().endsWith(".part")), "不应残留 .part");
        }
    }

    @Test
    @DisplayName("多文件包: 每个文件独立落地 —— 中间失败也要报告部分成功（不假装全成）")
    void multiFilePartialFailureIsVisible(@TempDir Path tmp) throws Exception {
        AssetFetch fetch = (url, max) -> {
            if (url.endsWith("bad.gif")) {
                throw new java.io.IOException("nope");
            }
            return FAKE_GIF;
        };
        AssetPack pack = new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                "", 0, "", "", List.of(
                        new AssetPack.FileEntry("good", "good.gif", FAKE_GIF.length, md5(FAKE_GIF),
                                "https://example.com/good.gif"),
                        new AssetPack.FileEntry("bad", "bad.gif", FAKE_GIF.length, md5(FAKE_GIF),
                                "https://example.com/bad.gif")),
                Map.of());

        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("p"), fetch, null);
        assertFalse(r.ok(), "有文件失败 ⇒ 不能报成功");
        assertTrue(r.message().contains("部分失败"), () -> r.message());
        assertTrue(r.message().contains("1 个已安装"), () -> r.message());
        assertEquals(1, r.assetCount(), "成功的那个应已落地（不等整包）");
        assertTrue(Files.isRegularFile(tmp.resolve("p/animations/good.gif")), "成功文件应立即落地");
    }

    @Test
    @DisplayName("工具: magic bytes 识别与 md5")
    void sniffAndMd5() {
        assertEquals("gif", AssetDownloader.sniff(FAKE_GIF));
        assertEquals("png", AssetDownloader.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0}));
        assertEquals("zip", AssetDownloader.sniff(new byte[] {'P', 'K', 3, 4, 0, 0, 0, 0}));
        assertNull(AssetDownloader.sniff(new byte[] {1, 2, 3}));
        assertEquals(md5(FAKE_GIF), AssetDownloader.md5Hex(FAKE_GIF));
    }

    // ── 测试夹具 ──────────────────────────────────────────────────────────

    private static byte[] zipOf(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 目标目录下是否存在任何动画文件（替代旧的"目录是否存在"断言）。 */
    private static boolean hasAnyAnimation(Path packDir) {
        Path anim = packDir.resolve("animations");
        if (!Files.isDirectory(anim)) {
            return false;
        }
        try (var list = Files.list(anim)) {
            return list.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return n.endsWith(".gif") || n.endsWith(".png");
            });
        } catch (Exception e) {
            return false;
        }
    }

    private static String md5(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── 断点续传 / 重试 / 限流（2026-09-23 实机暴露后补） ────────────────────

    @Test
    @DisplayName("断点续传: 已存在且校验通过的文件不再下载（第二次运行只补缺的）")
    void resumeSkipsVerifiedFiles(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("p");
        // 预置：good.gif 已存在且内容与清单一致
        Files.createDirectories(dir.resolve("animations"));
        Files.write(dir.resolve("animations/good.gif"), FAKE_GIF);

        byte[] gifB = "GIF89a-second-file-content-abcdef".getBytes(StandardCharsets.UTF_8);
        AssetPack pack = new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                "", 0, "", "", List.of(
                        new AssetPack.FileEntry("good", "good.gif", FAKE_GIF.length, md5(FAKE_GIF),
                                "https://example.com/good.gif"),
                        new AssetPack.FileEntry("new", "new.gif", gifB.length, md5(gifB),
                                "https://example.com/new.gif")),
                Map.of());

        java.util.List<String> fetched = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        AssetFetch spy = (url, max) -> {
            fetched.add(url);
            return url.endsWith("new.gif") ? gifB : FAKE_GIF;
        };
        AssetDownloader.Result r = AssetDownloader.download(pack, dir, spy, null);
        assertTrue(r.ok(), () -> r.message());
        assertEquals(2, r.assetCount(), "续传的 1 个 + 新下的 1 个");
        assertEquals(1, fetched.size(), () -> "只应下载缺的那 1 个，实际请求: " + fetched);
        assertTrue(fetched.get(0).endsWith("new.gif"));
    }

    @Test
    @DisplayName("断点续传: 大小或 md5 不符的文件会被重下（不信任坏文件）")
    void resumeRejectsCorruptedFile(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("p");
        Files.createDirectories(dir.resolve("animations"));
        // 预置同名但内容错误（模拟上次写入损坏/被篡改）
        Files.write(dir.resolve("animations/pet.gif"), "GIF89a-WRONG-CONTENT-xxxxxxxxxx".getBytes(StandardCharsets.UTF_8));

        AssetPack pack = new AssetPack("t", "t", "1", List.of("a"), "", "lic", "model",
                "", 0, "", "", List.of(new AssetPack.FileEntry("pet", "pet.gif", FAKE_GIF.length,
                        md5(FAKE_GIF), "https://example.com/pet.gif")),
                Map.of());
        AssetDownloader.Result r = AssetDownloader.download(pack, dir, serving(FAKE_GIF), null);
        assertTrue(r.ok(), () -> r.message());
        assertArrayEquals(FAKE_GIF, Files.readAllBytes(dir.resolve("animations/pet.gif")),
                "坏文件必须被覆盖成正确内容");
    }

    @Test
    @DisplayName("重试: 第一次失败、第二次成功的条目应在重试轮里补齐（不放弃）")
    void retriesTransientFailure(@TempDir Path tmp) throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        AssetFetch flaky = (url, max) -> {
            // 前 2 次失败，之后成功（模拟瞬时网络故障）
            if (attempts.incrementAndGet() <= 2) {
                throw new java.io.IOException("transient");
            }
            return FAKE_GIF;
        };
        AssetPack pack = packFor("pet.gif", FAKE_GIF, true);
        AssetDownloader.Result r = AssetDownloader.download(pack, tmp.resolve("p"), flaky, null);
        assertTrue(r.ok(), () -> "重试后应成功: " + r.message());
        assertTrue(attempts.get() >= 3, () -> "应尝试多次，实际 " + attempts.get());
        assertTrue(Files.isRegularFile(tmp.resolve("p/animations/pet.gif")));
    }

    @Test
    @DisplayName("重试: 持续失败 ⇒ 最终报失败且原因可见（不假装成功）")
    void persistentFailureIsReported(@TempDir Path tmp) {
        AssetFetch dead = (url, max) -> {
            throw new java.io.IOException("always down");
        };
        AssetDownloader.Result r = AssetDownloader.download(packFor("pet.gif", FAKE_GIF, true),
                tmp.resolve("p"), dead, null);
        assertFalse(r.ok());
        assertTrue(r.message().contains("全部文件下载失败") && r.message().contains("always down"),
                () -> "失败原因必须可见: " + r.message());
    }

    @Test
    @DisplayName("更新: 清单里已删除的旧动画会被清掉（保住整体替换语义）")
    void staleFilesPurgedOnUpdate(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("p");
        Files.createDirectories(dir.resolve("animations"));
        Files.write(dir.resolve("animations/removed.gif"), FAKE_GIF);   // 清单里没有它
        Files.write(dir.resolve("animations/junk.part"), FAKE_GIF);     // 残留临时文件

        AssetPack pack = packFor("kept.gif", FAKE_GIF, true);
        AssetDownloader.Result r = AssetDownloader.download(pack, dir, serving(FAKE_GIF), null);
        assertTrue(r.ok(), () -> r.message());
        assertTrue(Files.isRegularFile(dir.resolve("animations/kept.gif")));
        assertFalse(Files.exists(dir.resolve("animations/removed.gif")), "清单外的旧动画应被清掉");
        assertFalse(Files.exists(dir.resolve("animations/junk.part")), "残留 .part 应被清掉");
    }

    @Test
    @DisplayName("回归: pack.json 必须保留 animations 声明（否则渲染侧拿到空声明 ⇒ 显示错的动画）")
    void packJsonKeepsAnimations() {
        AssetPack pack = new AssetPack("default", "默认包", "0.2.11", List.of("PC2005-cloud"),
                "https://github.com/PC2005-cloud/dsh-pet", "open-source use, NON-COMMERCIAL", "model",
                "", 0, "", "", List.of(new AssetPack.FileEntry("beiluoye-yanmo", "beiluoye-yanmo.gif",
                        100, "abc", "https://e/a.gif"), new AssetPack.FileEntry("dongzhangxiwang",
                        "dongzhangxiwang.gif", 100, "def", "https://e/b.gif")),
                Map.of("idle", List.of("dongzhangxiwang"), "clicks", List.of("keai-zhaiwu")));

        String json = AssetDownloader.packToJson(pack);
        AssetPack roundTrip = AssetPack.of(AssetJson.parseObject(json));

        assertEquals(List.of("dongzhangxiwang"), roundTrip.animations().get("idle"),
                "animations.idle 必须往返存活（实机 bug：漏写 ⇒ 退化成字母序第一个）");
        assertEquals(List.of("keai-zhaiwu"), roundTrip.animations().get("clicks"));
        assertEquals("dongzhangxiwang", roundTrip.preferredAnimationName(),
                "首选动画必须是声明的 idle，而不是字母序第一个");
        assertEquals(2, roundTrip.downloadEntries().size(), "files 也要往返存活");
        assertTrue(json.contains("\"animations\""), () -> "json 里应有 animations 字段: " + json);
    }
}
