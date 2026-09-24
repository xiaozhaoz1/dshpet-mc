package com.github.xiaozhaoz1.dshpet.config.asset;

/**
 * 远端获取抽象 —— 让"清单获取/下载"逻辑**可单测**（注入假实现），且保持零 MC 依赖。
 *
 * <p>真实实现放平台无关的 `download` 包（用 JDK {@code HttpClient}，与 TLM 同类做法）；
 * 单测传 lambda 即可。</p>
 */
@FunctionalInterface
public interface AssetFetch {

    /**
     * 拉取指定 URL 的全部字节。
     *
     * @param url       绝对 URL
     * @param maxBytes  允许的最大字节数（超限必须抛异常，防超大响应打爆内存）
     * @return 响应体字节（非空）
     * @throws Exception 网络错误/HTTP 非 2xx/超限
     */
    byte[] fetch(String url, long maxBytes) throws Exception;

    /** 便捷：拉取文本（UTF-8）。 */
    default String fetchText(String url, long maxBytes) throws Exception {
        return new String(fetch(url, maxBytes), java.nio.charset.StandardCharsets.UTF_8);
    }
}
