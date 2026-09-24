package com.github.xiaozhaoz1.dshpet.config.asset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 素材包元数据（`pack.json` / 远端清单条目的**同一 schema**）—— 纯数据，零 MC 依赖。
 *
 * <p>设计依据（上游 dsh-pet + TLM 双实证）：下载单元是**包**（一包 = 一只宠物模型），
 * 不是单条动画。上游 {@code pets[]} + {@code pet/&lt;名&gt;-animation/} 是每模型一目录；
 * TLM {@code DownloadInfo.type=[maid,chair,sound]} 亦按包分类。</p>
 *
 * <p><b>文件条目模型（2026-09-23 修订）</b>：包内动画由 {@link FileEntry} 列表描述，
 * 每个条目自带 {@code url/fileSize/checksum} ⇒ 支持"素材分散在上游多个 URL"（例如直接从
 * 上游 raw 直链逐文件取），**本项目无需托管或分发任何受版权限制的素材本体**。</p>
 *
 * <p>兼容：旧格式的单 {@code url} + {@code fileName} 仍可用（作为"单文件包"）。</p>
 *
 * <p><b>署名单一来源</b>：{@link #authors()} 与 {@link #website()} 是 UI/命令/文档署名的唯一数据源。</p>
 *
 * @param id         包标识（目录名；仅允许 {@code [a-z0-9._-]}）
 * @param name       展示名
 * @param version    版本（与本地对比决定 {@code NEED_UPDATE}）
 * @param authors    作者列表（署名）
 * @param website    上游地址（二创署名要求：任何展示/分发处须附）
 * @param license    许可声明（上游素材为"禁止商用"口径）
 * @param category   分类（一期只有 {@code model}）
 * @param fileName   旧格式单文件名（多文件包留空）
 * @param fileSize   旧格式单文件大小（多文件包为各条目之和或 0）
 * @param checksum   旧格式单文件 md5（多文件包留空）
 * @param url        旧格式单文件直链（多文件包留空）
 * @param files      包内文件条目（多文件包的主字段）
 * @param animations 动画用途映射（键对齐上游池语义 idle/turn/drag/clicks/…）
 */
public record AssetPack(
        String id,
        String name,
        String version,
        List<String> authors,
        String website,
        String license,
        String category,
        String fileName,
        long fileSize,
        String checksum,
        String url,
        List<FileEntry> files,
        Map<String, List<String>> animations
) {

    /** 默认包 id（{@code /dshpet assets default} 下载并启用的包）。 */
    public static final String DEFAULT_PACK_ID = "default";

    /** 一期唯一分类。 */
    public static final String CATEGORY_MODEL = "model";

    public AssetPack {
        id = requireSafeId(id);
        name = orElse(name, id);
        version = orElse(version, "0");
        authors = authors == null ? List.of() : List.copyOf(authors);
        website = orElse(website, "");
        license = orElse(license, "unknown");
        category = orElse(category, CATEGORY_MODEL);
        fileName = orElse(fileName, "");
        checksum = orElse(checksum, "");
        url = orElse(url, "");
        files = files == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(files));
        animations = animations == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(animations));
    }

    /** 包内一条文件条目（多文件包用）。 */
    public record FileEntry(String name, String file, long fileSize, String checksum, String url,
                            List<String> altUrls) {

        public FileEntry {
            file = orElse(file, orElse(name, "unnamed") + ".gif");
            // name 用于"动画名"（渲染侧按名找文件）⇒ 派生时必须去掉扩展名
            name = orElse(name, stripExtension(file));
            checksum = orElse(checksum, "");
            url = orElse(url, "");
            altUrls = altUrls == null ? List.of() : List.copyOf(altUrls);
        }

        /** 兼容旧构造（无镜像）。 */
        public FileEntry(String name, String file, long fileSize, String checksum, String url) {
            this(name, file, fileSize, checksum, url, List.of());
        }

        /**
         * 全部候选 URL：**主 URL 在前，镜像按序在后**（去重、去空）。
         *
         * <p>为什么要多 URL：raw.githubusercontent 在国内常不可达 ⇒ 清单以 jsDelivr 为主，
         * 其余 CDN/raw 作兜底，下载器逐个尝试（2026-09-23 用户裁定）。</p>
         */
        public List<String> candidateUrls() {
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            if (!url.isBlank()) {
                set.add(url);
            }
            for (String u : altUrls) {
                if (u != null && !u.isBlank()) {
                    set.add(u);
                }
            }
            return List.copyOf(set);
        }

        /** 去掉最后一个扩展名（{@code a/b.gif → a/b}）。 */
        private static String stripExtension(String f) {
            int dot = f.lastIndexOf('.');
            return dot > 0 ? f.substring(0, dot) : f;
        }

        public boolean downloadable() {
            return !candidateUrls().isEmpty();
        }
    }

    /**
     * 展开成"待下载条目列表"：多文件包用 {@link #files}；旧格式回退为单条。
     *
     * <p>这样调用方（下载器）只需一套逻辑，不必区分新旧格式。</p>
     */
    public List<FileEntry> downloadEntries() {
        if (!files.isEmpty()) {
            List<FileEntry> out = new ArrayList<>();
            for (FileEntry f : files) {
                if (f.downloadable()) {
                    out.add(f);
                }
            }
            return out;
        }
        if (!url.isBlank()) {
            return List.of(new FileEntry(id, orElse(fileName, id + ".gif"), fileSize, checksum, url));
        }
        return List.of();
    }

    /** 是否有可下载内容。 */
    public boolean downloadable() {
        return !downloadEntries().isEmpty();
    }

    /** 声明的总字节数（多文件包取条目之和；条目未给大小则为 0）。 */
    public long totalDeclaredBytes() {
        long sum = 0;
        for (FileEntry f : downloadEntries()) {
            sum += Math.max(0, f.fileSize());
        }
        return sum;
    }

    /**
     * 署名行（UI 页脚 / {@code /dshpet about} / 聊天提示**共用同一份**，避免文案分叉）。
     */
    public String attribution() {
        String where = website.isBlank() ? "" : " · " + website;
        return authorsText() + where + " · " + license;
    }

    /** 作者展示串。 */
    public String authorsText() {
        return authors.isEmpty() ? "unknown" : String.join(", ", authors);
    }

    /** 一行摘要（日志/诊断；不含敏感信息）。 */
    public String summary() {
        int n = downloadEntries().size();
        String size = n == 1 ? String.valueOf(downloadEntries().get(0).fileSize()) : String.valueOf(totalDeclaredBytes());
        return id + " v" + version + " (" + n + " 个文件, " + size + "B)";
    }

    /**
     * 包内"默认动画名"：优先 {@code animations.idle} 的第一个，否则第一个文件条目的 name。
     *
     * <p>渲染侧用它决定加载哪个动画（素材不内嵌后不能写死动画名）。</p>
     */
    public String preferredAnimationName() {
        List<String> idle = animations.get("idle");
        if (idle != null && !idle.isEmpty()) {
            return idle.get(0);
        }
        List<FileEntry> entries = downloadEntries();
        return entries.isEmpty() ? "" : entries.get(0).name();
    }

    /** 由已解析的 JSON 映射构造（解析/容错在 {@link AssetJson}）。 */
    public static AssetPack of(Map<String, Object> raw) {
        List<String> authors = AssetJson.stringList(raw.get("author"));
        if (authors.isEmpty()) {
            authors = AssetJson.stringList(raw.get("authors"));
        }
        List<FileEntry> files = new ArrayList<>();
        for (Map<String, Object> row : AssetJson.objectList(raw.get("files"))) {
            files.add(new FileEntry(AssetJson.str(row, "name"), AssetJson.str(row, "file"),
                    AssetJson.lng(row, "fileSize"), AssetJson.str(row, "checksum"),
                    AssetJson.str(row, "url"), AssetJson.stringList(row.get("altUrls"))));
        }
        // 兼容旧格式：单 url 也转成一条条目，便于统一处理
        if (files.isEmpty()) {
            String legacyUrl = AssetJson.str(raw, "url");
            if (!legacyUrl.isBlank()) {
                files.add(new FileEntry(AssetJson.str(raw, "id"), AssetJson.str(raw, "fileName"),
                        AssetJson.lng(raw, "fileSize"), AssetJson.str(raw, "checksum"), legacyUrl));
            }
        }
        return new AssetPack(
                AssetJson.str(raw, "id"), AssetJson.str(raw, "name"), AssetJson.str(raw, "version"),
                authors, AssetJson.str(raw, "website"), AssetJson.str(raw, "license"),
                AssetJson.str(raw, "category"), AssetJson.str(raw, "fileName"),
                AssetJson.lng(raw, "fileSize"), AssetJson.str(raw, "checksum"), AssetJson.str(raw, "url"),
                files, AssetJson.stringListMap(raw.get("animations")));
    }

    /**
     * id 白名单校验 —— id 会被拼进文件路径，**必须防路径穿越**。
     */
    private static String requireSafeId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AssetPack.id 不能为空");
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException("AssetPack.id 不得含 '..': " + value);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException("AssetPack.id 含非法字符: " + value);
            }
        }
        return value;
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
