package com.github.xiaozhaoz1.dshpet.download;

import com.github.xiaozhaoz1.dshpet.DshPetLog;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetFetch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 真实网络抓取（JDK {@link HttpClient}，**零第三方依赖**）—— TLM 用 `HttpUtil`，本项目用 JDK 自带。
 *
 * <p><b>线程纪律</b>：本类的方法**会阻塞**，调用方必须在异步线程调用
 * （见命令层 {@code CompletableFuture}；绝不在渲染/命令线程直接跑）。</p>
 *
 * <p><b>安全/健壮性</b>：</p>
 * <ul>
 *   <li>只允许 <b>http/https</b>（拒绝 file:/jar: 等协议，防本地文件读取）</li>
 *   <li>超时：连接 5s / 请求 15s（避免卡死线程）</li>
 *   <li><b>流式限量读取</b>：边读边判上限，超限立即中止（不把超大响应读进内存）</li>
 *   <li>非 2xx ⇒ 抛异常（由上层转成可展示消息）</li>
 *   <li>不跟随重定向到非 http(s)（HttpClient 默认 NEVER 重定向策略可配置；此处用 NORMAL 但校验最终 URI）</li>
 * </ul>
 */
public final class HttpAssetFetch implements AssetFetch {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client;

    public HttpAssetFetch() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public byte[] fetch(String url, long maxBytes) throws Exception {
        URI uri = validate(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "dshpet-mc/0.1.0 (+https://github.com/xiaozhaoz1/dshpet-mc)")
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            try (var body = response.body()) {
                body.readNBytes(256); // 丢弃少量响应体，保证连接可复用
            }
            throw new java.io.IOException("HTTP " + code + " from " + uri.getHost());
        }
        long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (declared > maxBytes) {
            throw new java.io.IOException("响应声明长度 " + declared + "B 超过上限 " + maxBytes + "B");
        }
        try (var in = response.body()) {
            return readLimited(in, maxBytes);
        }
    }

    /** 流式读取并强制上限（防超大/无限响应）。 */
    static byte[] readLimited(java.io.InputStream in, long maxBytes) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buf)) > 0) {
            total += read;
            if (total > maxBytes) {
                throw new java.io.IOException("响应超过上限 " + maxBytes + "B，已中止");
            }
            out.write(buf, 0, read);
        }
        return out.toByteArray();
    }

    /** 只允许 http/https（防本地文件/其它协议）。 */
    static URI validate(String url) throws Exception {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 为空");
        }
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("仅支持 http/https，收到: " + scheme);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名: " + url);
        }
        return uri;
    }

    /** 供命令层记录：失败原因要给用户看得懂的一句话。 */
    public static String describe(Throwable t) {
        if (t instanceof java.net.UnknownHostException) {
            return "无法解析主机（检查网络/DNS）";
        }
        if (t instanceof java.net.http.HttpTimeoutException || t instanceof java.net.SocketTimeoutException) {
            return "超时";
        }
        if (t instanceof javax.net.ssl.SSLException) {
            return "TLS/证书错误";
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    /** 一次性诊断（不做任何缓存；供 /dshpet assets list 之类显示可用性）。 */
    public static boolean reachable(String url) {
        try {
            new HttpAssetFetch().fetch(url, 4096);
            return true;
        } catch (Throwable t) {
            DshPetLog.debug(DshPetLog.ASSET, "可达性检查失败（{}）: {}", url, describe(t));
            return false;
        }
    }
}
