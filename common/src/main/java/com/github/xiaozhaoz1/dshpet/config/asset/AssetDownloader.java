package com.github.xiaozhaoz1.dshpet.config.asset;

import com.github.xiaozhaoz1.dshpet.DshPetLog;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetPack.FileEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 素材包下载 + 校验 + 落盘 —— 纯逻辑（网络走 {@link AssetFetch} 注入），可单测。
 *
 * <p><b>下载策略（2026-09-23 用户裁定 + 实机优化）</b>：</p>
 * <ol>
 *   <li><b>主镜像优先，失败才升级为多镜像竞争</b>：同一文件的候选 URL 里，**先只打主镜像**
 *       （jsDelivr，国内可达）；仅当它失败时才并行打其余镜像抢首个成功者。
 *       依据（2026-09-23 两轮实机测量）：无脑 5 镜像全竞速会浪费 4/5 的请求额度
 *       （限流下互相抢许可 ⇒ 实测 1544 ms/文件）；主优先则 106 文件里绝大多数一次直取。</li>
 *   <li><b>单文件校验通过即落地</b>：每文件独立「下载 → magic bytes → 大小 → md5 → 原子写入」，
 *       不再等整包下完。依据：整包原子移动会让 106 个文件的进度全压在最后一步（用户实测反馈
 *       "看不到任何进展"）。</li>
 *   <li><b>断点续传</b>：已存在且 **大小/md5 双验通过**的文件直接跳过（下次运行只补缺的）。</li>
 *   <li><b>失败重试</b>：失败条目进入下一轮（最多 {@link #MAX_ROUNDS} 轮），轮间退避。</li>
 *   <li><b>全局限流</b>：所有 HTTP 请求共享 {@link #MAX_CONCURRENT_REQUESTS} 个许可 —— 2026-09-23 实机
 *       教训：4 文件 × 5 镜像 = 20 并发把自己拖垮（HttpClient 内部排队 ⇒ `HttpTimeoutException`
 *       18/106），CDN 其实是好的。</li>
 *   <li><b>失败要可见</b>：每个失败都打 WARN（带镜像 host 与原因）—— 之前失败路径静默，
 *       导致实机出问题时**无从诊断**。</li>
 * </ol>
 *
 * <p><b>安全</b>：zip 解包抗 zip-slip（丢弃目录结构）与 zip 炸弹（条目数/展开总量上限）；
 * 只接受 gif/png/zip；清单给了 md5 就必校。</p>
 */
public final class AssetDownloader {

    /** 单文件硬上限（清单声明值再大也不超过它）。 */
    public static final long HARD_MAX_BYTES = 32L * 1024L * 1024L;

    /** 解包后的条目数上限（zip 炸弹防护）。 */
    private static final int MAX_ENTRIES = 2048;

    /** 解包后总展开大小上限（zip 炸弹防护）。 */
    private static final long MAX_UNPACKED_BYTES = 128L * 1024L * 1024L;

    /** 允许的动画扩展名（白名单）。 */
    private static final String[] ASSET_EXTS = {".gif", ".png"};

    /** 并行镜像数上限（防候选列表异常膨胀时开太多线程）。 */
    private static final int MAX_PARALLEL_MIRRORS = 8;

    /** 全局并发 HTTP 请求上限（限流，防把自己拖垮 —— 见类注释的实机教训）。 */
    private static final int MAX_CONCURRENT_REQUESTS = 12;

    /** 失败重试轮数（含首轮）。 */
    private static final int MAX_ROUNDS = 3;

    /** 轮间退避基数（毫秒；第 n 轮等待 n * 该值）。 */
    private static final long RETRY_BACKOFF_MS = 700;

    /**
     * 进度回调。
     *
     * <p>{@link #onFileDone} 在**每个文件成功落地后**调用一次 —— 供"聊天栏每下一个文件更新一条提示"
     * 使用（用户 2026-09-23 要求：更新提示而非刷屏）。</p>
     */
    public interface Progress {
        /** 总进度变化（0..100）。 */
        default void onProgress(int percent) {
        }

        /** 单个文件落地完成（done 从 1 开始）。 */
        default void onFileDone(int done, int total, String file) {
        }
    }

    /** 结果（成功/部分成功/失败都带**可展示的原因**）。 */
    public record Result(boolean ok, DownloadStatus status, String message, int assetCount) {
        static Result ok(int count) {
            return new Result(true, DownloadStatus.DOWNLOADED, "已安装（" + count + " 个动画文件）", count);
        }

        static Result partial(int count, int failed, String firstReason) {
            return new Result(false, DownloadStatus.FAILED,
                    "部分失败：" + count + " 个已安装，" + failed + " 个失败（首个原因: " + firstReason + "）",
                    count);
        }

        static Result fail(String message) {
            return new Result(false, DownloadStatus.FAILED, message, 0);
        }
    }

    private AssetDownloader() {
    }

    /**
     * 下载并安装一个包到 {@code packDir}：**逐文件并行竞争下载 → 校验 → 立即落地**。
     *
     * @param pack     清单条目（多文件 {@code files} 或旧格式单 {@code url}）
     * @param packDir  目标目录（不存在则创建）
     * @param fetch    网络获取（真实/测试注入）
     * @param progress 进度回调（可为 null）
     */
    public static Result download(AssetPack pack, Path packDir, AssetFetch fetch, Progress progress) {
        if (pack == null) {
            return Result.fail("包信息缺失");
        }
        List<FileEntry> all = pack.downloadEntries();
        if (all.isEmpty()) {
            return Result.fail("该包没有可下载的内容（清单未配置 url）");
        }
        Path animDir = packDir.resolve("animations");
        try {
            Files.createDirectories(animDir);
        } catch (Throwable t) {
            return Result.fail("无法创建目标目录: " + t.getMessage());
        }

        int total = all.size();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicReference<String> firstReason = new AtomicReference<>("");
        Semaphore permits = new Semaphore(MAX_CONCURRENT_REQUESTS);
        CompletableFuture<Boolean> cancelled = new CompletableFuture<>(); // 供镜像竞速提前收敛

        report(progress, 0); // 起始进度（重写时曾漏掉 —— 测试抓到）

        // ① 断点续传：已存在且"大小 + md5"双验通过的条目直接跳过
        List<FileEntry> pending = new ArrayList<>();
        int resumed = 0;
        for (FileEntry e : all) {
            if (isAlreadyComplete(e, animDir)) {
                resumed++;
                done.incrementAndGet();
            } else {
                pending.add(e);
            }
        }
        if (resumed > 0) {
            DshPetLog.info(DshPetLog.ASSET, "断点续传：{} 个文件已存在且校验通过，本次只需下载 {} 个",
                    resumed, pending.size());
        }
        // 清掉"不在预期集合里"的旧动画（保住"更新时整体替换"语义，但不动已续传的文件）
        purgeStale(animDir, all);

        int fileThreads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        ExecutorService filePool = Executors.newFixedThreadPool(fileThreads);
        List<FileEntry> remaining = new ArrayList<>(pending);
        try {
            // ② 分轮重试：每轮对"仍未完成"的条目并发下载，失败者进入下一轮
            for (int round = 1; round <= MAX_ROUNDS && !remaining.isEmpty(); round++) {
                if (round > 1) {
                    long backoff = RETRY_BACKOFF_MS * (round - 1);
                    DshPetLog.info(DshPetLog.ASSET, "第 {} 轮重试：剩余 {} 个失败条目，退避 {}ms",
                            round, remaining.size(), backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                List<FileEntry> failedThisRound = java.util.Collections.synchronizedList(new ArrayList<>());
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                final int currentRound = round; // lambda 捕获需 effectively-final
                for (FileEntry entry : remaining) {
                    futures.add(CompletableFuture.runAsync(() -> {
                        EntryResult er = downloadOne(entry, animDir, fetch, permits);
                        if (er.ok()) {
                            int d = done.addAndGet(Math.max(1, er.written()));
                            report(progress, (int) (Math.min(d, total) * 100L / total));
                            reportFile(progress, Math.min(d, total), total, entry.file());
                        } else {
                            failedThisRound.add(entry);
                            if (currentRound == MAX_ROUNDS) {
                                failed.incrementAndGet();
                                firstReason.compareAndSet("", entry.file() + ": " + er.reason());
                                // 失败必须留痕（可诊断）—— 之前静默导致实机无从定位
                                DshPetLog.warn(DshPetLog.ASSET, "下载失败（{} 轮后放弃）{}: {}",
                                        MAX_ROUNDS, entry.file(), er.reason());
                            }
                        }
                    }, filePool));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                remaining = new ArrayList<>(failedThisRound);
            }
        } finally {
            filePool.shutdownNow();
            cancelled.complete(true);
        }

        int okCount = done.get();
        if (okCount == 0) {
            return Result.fail(firstReason.get().isEmpty()
                    ? "全部文件下载失败"
                    : "全部文件下载失败（首个原因: " + firstReason.get() + "）");
        }
        try {
            Files.writeString(packDir.resolve("pack.json"), packToJson(pack), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            DshPetLog.warn(DshPetLog.ASSET, "写入 pack.json 失败（不影响素材使用）: {}", t.toString());
        }
        if (remaining.isEmpty()) {
            report(progress, 100);
            DshPetLog.info(DshPetLog.ASSET, "素材包已安装: {} ⇒ {}（{} 个文件{}）",
                    pack.summary(), packDir, okCount, resumed > 0 ? "，含续传 " + resumed : "");
            return Result.ok(okCount);
        }
        return Result.partial(okCount, remaining.size(), firstReason.get());
    }

    /**
     * 条目是否"已完整"（断点续传判据）：文件存在，且**大小相等**（清单声明 >0 时）
     * 且 **md5 相符**（清单给了 md5 时）。zip 条目不做续传（解包产物无法一一对应）。
     */
    static boolean isAlreadyComplete(FileEntry entry, Path animDir) {
        if (entry.file().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return false;
        }
        Path f = animDir.resolve(entry.file());
        if (!Files.isRegularFile(f)) {
            return false;
        }
        try {
            if (entry.fileSize() > 0 && Files.size(f) != entry.fileSize()) {
                return false;
            }
            if (!entry.checksum().isBlank()) {
                String actual = md5Hex(Files.readAllBytes(f));
                return actual.equalsIgnoreCase(entry.checksum());
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 清掉**不在预期集合内**的旧动画与残留 .part（保住"更新时整体替换"语义，
     * 同时不误删已续传的文件）。
     */
    private static void purgeStale(Path animDir, List<FileEntry> expected) {
        if (!Files.isDirectory(animDir)) {
            return;
        }
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (FileEntry e : expected) {
            if (!e.file().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                keep.add(e.file());
            }
        }
        try (var list = Files.list(animDir)) {
            for (Path f : list.toList()) {
                String n = f.getFileName().toString();
                String lower = n.toLowerCase(Locale.ROOT);
                boolean isAsset = lower.endsWith(".gif") || lower.endsWith(".png");
                if (lower.endsWith(".part") || (isAsset && !keep.contains(n))) {
                    try {
                        Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                        // 尽力而为
                    }
                }
            }
        } catch (IOException e) {
            DshPetLog.warn(DshPetLog.ASSET, "清理旧素材失败（继续下载）: {}", e.toString());
        }
    }

    /** 单条目结果：成功带类型与落盘文件数；失败带**可展示的原因**。 */
    private record EntryResult(boolean ok, String kind, String reason, int written) {
        static EntryResult ok(String kind, int written) {
            return new EntryResult(true, kind, "", written);
        }

        static EntryResult fail(String reason) {
            return new EntryResult(false, null, reason, 0);
        }
    }

    /**
     * 单条目：**多镜像并行竞争** → 第一个校验通过者胜出 → 用它的字节落盘。
     *
     * <p>与 {@code anyOf} 的关键区别：{@code anyOf} 会被**第一个完成者**唤醒（哪怕是失败），
     * 这里要的是"第一个**成功**者" ⇒ 自己维护一个 future，只在成功或**全部失败**时才 complete。</p>
     */
    private static EntryResult downloadOne(FileEntry entry, Path animDir, AssetFetch fetch,
                                           Semaphore permits) {
        long limit = entry.fileSize() > 0
                ? Math.min(entry.fileSize(), HARD_MAX_BYTES)
                : HARD_MAX_BYTES;
        if (entry.fileSize() > HARD_MAX_BYTES) {
            String why = "体积 " + entry.fileSize() + "B 超过上限 " + HARD_MAX_BYTES + "B";
            DshPetLog.warn(DshPetLog.ASSET, "{} {}，已拒绝", entry.file(), why);
            return EntryResult.fail(why);
        }
        List<String> candidates = entry.candidateUrls();
        if (candidates.isEmpty()) {
            return EntryResult.fail("没有可用下载地址");
        }
        // ① 先只打主镜像（省流量、省限流额度；实测这是绝大多数文件的情形）
        EntryResult primary = fetchAndVerify(entry, animDir, fetch, candidates.get(0), limit, permits);
        if (primary.ok()) {
            return primary;
        }
        if (candidates.size() == 1) {
            return primary;
        }
        // ② 主镜像失败 ⇒ 升级为"其余镜像并行竞争首个成功者"
        DshPetLog.info(DshPetLog.ASSET, "{} 主镜像失败（{}），升级为多镜像竞争（{} 个备用）",
                entry.file(), primary.reason(), candidates.size() - 1);

        CompletableFuture<EntryResult> winner = new CompletableFuture<>();
        AtomicInteger settled = new AtomicInteger();
        AtomicReference<String> lastReason = new AtomicReference<>("未知原因");
        List<String> backup = candidates.subList(1, candidates.size());
        int n = Math.min(backup.size(), MAX_PARALLEL_MIRRORS);
        for (int i = 0; i < n; i++) {
            final String url = backup.get(i);
            // 镜像竞速走 commonPool：ForkJoinPool.commonPool 在等待时会补偿线程，不会出现
            // "父任务阻塞子任务"的饥饿（这是与"共用固定池"的本质区别）
            CompletableFuture.runAsync(() -> {
                if (winner.isDone()) {
                    return; // 已有胜者 ⇒ 不再发起（省流量）
                }
                EntryResult r = fetchAndVerify(entry, animDir, fetch, url, limit, permits);
                if (r.ok()) {
                    if (winner.complete(r)) {
                        DshPetLog.info(DshPetLog.ASSET, "{} 由镜像 {} 胜出",
                                entry.file(), AssetCatalog.shortUrl(url));
                    }
                } else {
                    lastReason.set(r.reason());
                    if (settled.incrementAndGet() >= n) {
                        winner.complete(EntryResult.fail(lastReason.get()));
                    }
                }
            }); // 不传 executor ⇒ ForkJoinPool.commonPool（可按需补偿线程）
        }
        try {
            return winner.join();
        } catch (Throwable t) {
            return EntryResult.fail("下载竞争失败: " + t);
        }
    }

    /**
     * 单 URL：下载 → 校验（大小/类型/md5）→ 落盘。**竞争与串行路径共用**。
     *
     * <p>注意：本方法会真正写盘（校验通过后），因此竞速时**只有胜者的写盘生效**；
     * 落败者若已写完同一文件，内容也必然相同（md5 已校）⇒ 无害。</p>
     */
    private static EntryResult fetchAndVerify(FileEntry entry, Path animDir, AssetFetch fetch,
                                              String url, long limit, Semaphore permits) {
        byte[] data;
        try {
            // 全局限流：拿到许可才发请求（防"自己打自己"，见类注释实机教训）
            if (!permits.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS)) {
                return EntryResult.fail("并发已满，等待超时（稍后重试）");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return EntryResult.fail("等待下载许可被中断");
        }
        try {
            data = fetch.fetch(url, limit);
        } catch (Throwable t) {
            return EntryResult.fail("下载失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : " " + t.getMessage()));
        } finally {
            permits.release();
        }
        if (data == null || data.length == 0 || data.length > limit) {
            return EntryResult.fail("响应体积异常: " + (data == null ? -1 : data.length)
                    + "B（上限 " + limit + "B）");
        }
        if (entry.fileSize() > 0 && entry.fileSize() != data.length) {
            return EntryResult.fail("体积与声明不符（声明 " + entry.fileSize()
                    + "B，实际 " + data.length + "B）");
        }
        String kind = sniff(data);
        if (kind == null) {
            return EntryResult.fail("文件类型不识别（既不是 zip 也不是 gif/png）");
        }
        if (!entry.checksum().isBlank()) {
            String actual = md5Hex(data);
            if (!actual.equalsIgnoreCase(entry.checksum())) {
                return EntryResult.fail("校验失败（期望 md5 " + shortMd5(entry.checksum())
                        + "，实际 " + shortMd5(actual) + "）");
            }
        }
        try {
            if ("zip".equals(kind)) {
                int n = unzipInto(data, animDir);
                return n == 0
                        ? EntryResult.fail("包内没有可用的动画文件（.gif/.png）")
                        : EntryResult.ok(kind, n);
            }
            String ext = "png".equals(kind) ? ".png" : ".gif";
            String name = sanitizeFileName(entry.file());
            if (name.isBlank()) {
                name = entry.name() + ext;
            }
            // 先写 .part 再原子移动：并发下不会出现"半写文件被渲染线程读到"
            Path tmp = animDir.resolve(name + ".part");
            Files.write(tmp, data);
            Files.move(tmp, animDir.resolve(name),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return EntryResult.ok(kind, 1);
        } catch (Throwable t) {
            return EntryResult.fail("写入失败: " + t.getMessage());
        }
    }

    // ── zip 解包（zip-slip / zip 炸弹防护） ─────────────────────────────────

    /**
     * 解包 zip 到目标目录（**含 zip-slip 与 zip 炸弹防护**）。
     *
     * @return 落盘的动画文件数
     */
    static int unzipInto(byte[] zipBytes, Path targetDir) throws IOException {
        int entries = 0;
        long totalBytes = 0;
        int assetCount = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("zip 条目过多（>" + MAX_ENTRIES + "），已中止");
                }
                String name = e.getName();
                if (e.isDirectory()) {
                    continue;
                }
                String base = baseName(name);
                if (!hasAllowedExt(base)) {
                    continue;
                }
                String safe = sanitizeFileName(base);
                if (safe.isBlank()) {
                    continue;
                }
                byte[] content = readAll(zis, MAX_UNPACKED_BYTES - totalBytes);
                totalBytes += content.length;
                if (totalBytes > MAX_UNPACKED_BYTES) {
                    throw new IOException("zip 解包总量超过 " + MAX_UNPACKED_BYTES + "B，已中止");
                }
                Files.write(targetDir.resolve(safe), content);
                assetCount++;
            }
        }
        return assetCount;
    }

    /** 取路径的最后一段（同时丢弃 Windows 风格的目录分隔）。 */
    private static String baseName(String entryName) {
        String s = entryName.replace('\\', '/');
        int idx = s.lastIndexOf('/');
        return idx >= 0 ? s.substring(idx + 1) : s;
    }

    private static String sanitizeFileName(String name) {
        String s = baseName(name);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == ' ';
            if (ok) {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        return out.contains("..") ? "" : out;
    }

    private static boolean hasAllowedExt(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ASSET_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(InputStream in, long remaining) throws IOException {
        if (remaining <= 0) {
            throw new IOException("zip 解包总量超限");
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int read;
        long total = 0;
        while ((read = in.read(buf)) > 0) {
            total += read;
            if (total > remaining) {
                throw new IOException("单个条目超过剩余配额");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    /** magic bytes 识别：zip / png / gif。 */
    static String sniff(byte[] d) {
        if (d == null || d.length < 8) {
            return null;
        }
        if (d[0] == 'P' && d[1] == 'K' && (d[2] == 3 || d[2] == 5 || d[2] == 7)) {
            return "zip";
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "png";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "gif";
        }
        return null;
    }

    static String md5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String shortMd5(String md5) {
        return md5 == null ? "?" : (md5.length() > 8 ? md5.substring(0, 8) + "…" : md5);
    }

    private static void report(Progress p, int percent) {
        if (p != null) {
            try {
                p.onProgress(percent);
            } catch (Throwable ignored) {
                // 进度回调异常不得影响下载
            }
        }
    }

    private static void reportFile(Progress p, int done, int total, String file) {
        if (p != null) {
            try {
                p.onFileDone(done, total, file);
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    /** 把包元数据序列化成 `pack.json`（手写小对象，字段与解析端对称）。 */
    static String packToJson(AssetPack p) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"id\": \"").append(escape(p.id())).append("\",\n");
        sb.append("  \"name\": \"").append(escape(p.name())).append("\",\n");
        sb.append("  \"version\": \"").append(escape(p.version())).append("\",\n");
        sb.append("  \"author\": [");
        for (int i = 0; i < p.authors().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(escape(p.authors().get(i))).append('"');
        }
        sb.append("],\n");
        sb.append("  \"website\": \"").append(escape(p.website())).append("\",\n");
        sb.append("  \"license\": \"").append(escape(p.license())).append("\",\n");
        sb.append("  \"category\": \"").append(escape(p.category())).append("\",\n");
        sb.append("  \"fileName\": \"").append(escape(p.fileName())).append("\",\n");
        sb.append("  \"fileSize\": ").append(p.fileSize()).append(",\n");
        sb.append("  \"checksum\": \"").append(escape(p.checksum())).append("\",\n");
        sb.append("  \"url\": \"").append(escape(p.url())).append("\",\n");
        sb.append("  \"files\": [");
        List<FileEntry> entries = p.downloadEntries();
        for (int i = 0; i < entries.size(); i++) {
            FileEntry f = entries.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\n    {\"name\": \"").append(escape(f.name()))
                    .append("\", \"file\": \"").append(escape(f.file()))
                    .append("\", \"fileSize\": ").append(f.fileSize())
                    .append(", \"checksum\": \"").append(escape(f.checksum()))
                    .append("\", \"url\": \"").append(escape(f.url())).append("\"");
            if (!f.altUrls().isEmpty()) {
                sb.append(", \"altUrls\": [");
                for (int j = 0; j < f.altUrls().size(); j++) {
                    sb.append(j > 0 ? ", " : "").append("\"")
                            .append(escape(f.altUrls().get(j))).append("\"");
                }
                sb.append("]");
            }
            sb.append("}");
        }
        sb.append("\n  ]");
        // animations 必须落盘：渲染侧靠 pack.json 的 animations.idle 决定"加载哪个动画"。
        // 2026-09-23 实机 bug：漏写此字段 ⇒ 读回为空 ⇒ 退化成"目录内字母序第一个"
        // ⇒ 显示的动画不是声明的默认动画（实机表现为"桌宠长得不对"）。
        writeAnimations(sb, p);
        sb.append("\n}\n");
        return sb.toString();
    }

    /** 序列化 {@code animations}（用途 → 动画名列表），字段与 {@link AssetPack#of} 的解析端对称。 */
    private static void writeAnimations(StringBuilder sb, AssetPack p) {
        if (p.animations().isEmpty()) {
            return;
        }
        sb.append(",\n  \"animations\": {");
        boolean firstUsage = true;
        for (Map.Entry<String, List<String>> e : p.animations().entrySet()) {
            if (!firstUsage) {
                sb.append(",");
            }
            firstUsage = false;
            sb.append("\"").append(escape(e.getKey())).append("\": [");
            List<String> names = e.getValue();
            for (int i = 0; i < names.size(); i++) {
                sb.append(i > 0 ? ", " : "").append("\"").append(escape(names.get(i))).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
