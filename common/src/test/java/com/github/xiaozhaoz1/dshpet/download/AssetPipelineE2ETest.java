package com.github.xiaozhaoz1.dshpet.download;

import com.github.xiaozhaoz1.dshpet.config.ConfigPaths;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetCatalog;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetDownloader;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetManifest;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetPack;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **端到端**：本地 HTTP 服务器 → 清单获取 → 下载 → 校验 → 解包 → 入库。
 *
 * <p>为什么值得单测跑一遍真 HTTP（用户裁定：看不见画面，但参数要验证）：这条链路跨越
 * 清单解析/主备切换/HTTP/限量读取/md5/zip 解包/目录布局，任何一环在真实网络下才暴露的
 * 问题（如 content-length、非 2xx、超限）都能在此覆盖，且**不依赖外网**。</p>
 */
class AssetPipelineE2ETest {

    private HttpServer server;
    private int port;

    /** 假 GIF（含 magic bytes，供 sniff 通过）。 */
    private static final byte[] GIF = "GIF89a-0000000000000000".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty(AssetCatalog.PROP_PRIMARY);
        System.clearProperty(AssetCatalog.PROP_BACKUP);
    }

    private void serve(String path, byte[] body, int status) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    @DisplayName("端到端: 清单 → 下载 zip → md5 校验 → 解包入库 → 可扫描到")
    void fullPipeline(@TempDir Path tmp) throws Exception {
        ConfigPaths.init(tmp);
        byte[] zip = zipOf(Map.of("animations/pet.gif", GIF));
        String md5 = md5Hex(zip);

        String manifestJson = """
                {
                  "manifestVersion": 1,
                  "assets": [{
                    "id": "default", "name": "默认包", "version": "1.0",
                    "author": ["PC2005-cloud"],
                    "website": "https://github.com/PC2005-cloud/dsh-pet",
                    "license": "open-source use, NON-COMMERCIAL",
                    "category": "model", "fileName": "default.zip",
                    "fileSize": %d, "checksum": "%s",
                    "url": "%s/pack.zip"
                  }]
                }
                """.formatted(zip.length, md5, url(""));
        serve("/index.json", manifestJson.getBytes(StandardCharsets.UTF_8), 200);
        serve("/pack.zip", zip, 200);
        System.setProperty(AssetCatalog.PROP_PRIMARY, url("/index.json"));

        // ① 清单
        AssetManifest manifest = AssetCatalog.load(new HttpAssetFetch(), ConfigPaths.manifestCacheFile(), "0.1.0");
        assertEquals(1, manifest.packs().size(), "应解析出 1 个包");
        AssetPack pack = manifest.defaultPack();
        assertNotNull(pack);
        assertEquals("PC2005-cloud", pack.authorsText());
        assertTrue(pack.attribution().contains("NON-COMMERCIAL"), "署名应含许可口径");

        // ② 下载
        AssetDownloader.Result r = AssetDownloader.download(pack, AssetStore.packDir("default"),
                new HttpAssetFetch(), null);
        assertTrue(r.ok(), () -> "下载应成功: " + r.message());
        assertEquals(1, r.assetCount());

        // ③ 入库可扫描 + 署名可读（离线展示依赖 pack.json）
        var installed = AssetStore.installed();
        assertEquals(1, installed.size());
        assertEquals("default", installed.get(0).id());
        assertEquals("PC2005-cloud", installed.get(0).pack().authorsText());
        assertTrue(Files.isRegularFile(AssetStore.packDir("default").resolve("animations/pet.gif")));
    }

    @Test
    @DisplayName("端到端: 主 URL 500 ⇒ 自动切备用 URL（照 TLM 主备设计）")
    void fallsBackToBackupUrl(@TempDir Path tmp) throws Exception {
        ConfigPaths.init(tmp);
        serve("/bad.json", "boom".getBytes(StandardCharsets.UTF_8), 500);
        serve("/good.json", """
                {"manifestVersion":1,"assets":[{"id":"x","url":"http://127.0.0.1:%d/none.zip"}]}
                """.formatted(port).getBytes(StandardCharsets.UTF_8), 200);
        System.setProperty(AssetCatalog.PROP_PRIMARY, url("/bad.json"));
        System.setProperty(AssetCatalog.PROP_BACKUP, url("/good.json"));

        AssetManifest manifest = AssetCatalog.load(new HttpAssetFetch(), null, "0.1.0");
        assertEquals(1, manifest.packs().size(), "主 URL 失败后应使用备用 URL");
        assertEquals("x", manifest.packs().get(0).id());
    }

    @Test
    @DisplayName("端到端: 全部 URL 失败 ⇒ 回落本地缓存（离线可用）")
    void fallsBackToLocalCache(@TempDir Path tmp) throws Exception {
        ConfigPaths.init(tmp);
        // 先写一份合法缓存
        Path cache = ConfigPaths.manifestCacheFile();
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, """
                {"manifestVersion":1,"assets":[{"id":"cached","url":"http://127.0.0.1:%d/none.zip"}]}
                """.formatted(port), StandardCharsets.UTF_8);
        System.setProperty(AssetCatalog.PROP_PRIMARY, url("/missing.json"));
        System.setProperty(AssetCatalog.PROP_BACKUP, url("/also-missing.json"));

        AssetManifest manifest = AssetCatalog.load(new HttpAssetFetch(), cache, "0.1.0");
        assertEquals(1, manifest.packs().size(), "网络全失败应回落本地缓存");
        assertEquals("cached", manifest.packs().get(0).id());
    }

    @Test
    @DisplayName("端到端: 网络不可达 ⇒ 空清单（降级不崩）")
    void degradesGracefully(@TempDir Path tmp) {
        ConfigPaths.init(tmp);
        System.setProperty(AssetCatalog.PROP_PRIMARY, "http://127.0.0.1:1/never.json");
        System.setProperty(AssetCatalog.PROP_BACKUP, "");
        AssetManifest manifest = AssetCatalog.load(new HttpAssetFetch(), null, "0.1.0", false);
        assertTrue(manifest.packs().isEmpty(),
                "全失败且禁用内置回落 ⇒ 空清单（界面显示提示，不抛异常）");
        assertFalse(manifest.supported() && !manifest.packs().isEmpty());
    }

    @Test
    @DisplayName("端到端: 服务端返回超大响应 ⇒ 限量读取中止（不把内存吃满）")
    void oversizedResponseIsAborted(@TempDir Path tmp) throws Exception {
        ConfigPaths.init(tmp);
        serve("/big.bin", new byte[200 * 1024], 200);
        assertThrowsLikeIo(() -> new HttpAssetFetch().fetch(url("/big.bin"), 64 * 1024));
    }

    private static void assertThrowsLikeIo(ThrowingRunnable r) {
        try {
            r.run();
            throw new AssertionError("应因超限而失败，但没有");
        } catch (Throwable t) {
            assertTrue(t.getMessage() == null || t.getMessage().contains("上限")
                            || t instanceof java.io.IOException,
                    () -> "应是 IO/超限异常，实际: " + t);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

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

    private static String md5Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("端到端: 主镜像 500 ⇒ 自动切备用镜像（国内 CDN 兜底链路）")
    void fallsBackToAltMirror(@TempDir Path tmp) throws Exception {
        ConfigPaths.init(tmp);
        byte[] gif = "GIF89a-mirror-fallback-test-0123456789".getBytes(StandardCharsets.UTF_8);
        String md5 = md5Hex(gif);

        serve("/broken.gif", "boom".getBytes(StandardCharsets.UTF_8), 500);
        serve("/good.gif", gif, 200);

        // 条目：主 URL 指向会失败的镜像，altUrls 指向可用镜像
        AssetPack pack = new AssetPack("default", "n", "1", List.of("PC2005-cloud"), "", "lic", "model",
                "", 0, "", "", List.of(new AssetPack.FileEntry(
                        "pet", "pet.gif", gif.length, md5, url("/broken.gif"), List.of(url("/good.gif")))),
                Map.of("idle", List.of("pet")));

        AssetDownloader.Result r = AssetDownloader.download(pack, AssetStore.packDir("default"),
                new HttpAssetFetch(), null);
        assertTrue(r.ok(), () -> "主镜像失败后应切备用镜像: " + r.message());
        assertEquals(1, r.assetCount());
        assertTrue(Files.isRegularFile(AssetStore.packDir("default").resolve("animations/pet.gif")));

        // 且入库后可被扫描到（署名随 pack.json 落盘）
        var installed = AssetStore.installed();
        assertEquals(1, installed.size());
        assertEquals("PC2005-cloud", installed.get(0).pack().authorsText());
    }

    @Test
    @DisplayName("端到端: 所有镜像都失败 ⇒ 报错信息里说明尝试了几个镜像")
    void allMirrorsFail(@TempDir Path tmp) throws Exception {
        ConfigPaths.init(tmp);
        serve("/dead1.gif", "x".getBytes(StandardCharsets.UTF_8), 500);
        serve("/dead2.gif", "x".getBytes(StandardCharsets.UTF_8), 404);
        AssetPack pack = new AssetPack("default", "n", "1", List.of("a"), "", "lic", "model",
                "", 0, "", "", List.of(new AssetPack.FileEntry(
                        "pet", "pet.gif", 0, "", url("/dead1.gif"),
                        List.of(url("/dead2.gif"), url("/dead3.gif")))),
                Map.of());
        AssetDownloader.Result r = AssetDownloader.download(pack, AssetStore.packDir("default"),
                new HttpAssetFetch(), null);
        assertFalse(r.ok());
        assertTrue(r.message().contains("全部文件下载失败") || r.message().contains("镜像"),
                () -> "应说明失败原因: " + r.message());
        assertTrue(r.message().contains("http") || r.message().contains("下载失败"),
                () -> "原因里应含网络层信息: " + r.message());
    }
}
