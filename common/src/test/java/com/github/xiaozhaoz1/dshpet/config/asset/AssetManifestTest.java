package com.github.xiaozhaoz1.dshpet.config.asset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssetJson} / {@link AssetManifest} / {@link AssetPack} 单测 —— 纯 JVM，零 MC 依赖。
 *
 * <p>覆盖：语法正确性、类型容错、坏条目跳过、id 路径穿越防护、署名单一来源。</p>
 */
class AssetManifestTest {

    private static final String GOOD = """
            {
              "manifestVersion": 1,
              "minModVersion": "0.1.0",
              "assets": [
                {
                  "id": "default",
                  "name": "默认素材包",
                  "version": "0.2.11",
                  "author": ["PC2005-cloud"],
                  "website": "https://github.com/PC2005-cloud/dsh-pet",
                  "license": "open-source use, NON-COMMERCIAL",
                  "category": "model",
                  "fileName": "default.zip",
                  "fileSize": 1240705,
                  "checksum": "0123456789abcdef0123456789abcdef",
                  "url": "https://example.com/default.zip",
                  "animations": { "idle": ["dongzhangxiwang"], "clicks": ["keai-zhaiwu"] }
                }
              ]
            }
            """;

    @Test
    @DisplayName("解析正常清单：字段/嵌套数组/署名")
    void parseGoodManifest() {
        AssetManifest m = AssetManifest.parse(GOOD);
        assertEquals(1, m.manifestVersion());
        assertEquals("0.1.0", m.minModVersion());
        assertEquals(1, m.packs().size());

        AssetPack p = m.defaultPack();
        assertNotNull(p, "应能按 id 找到默认包");
        assertEquals("default", p.id());
        assertEquals("默认素材包", p.name());
        assertEquals("0.2.11", p.version());
        assertEquals(java.util.List.of("PC2005-cloud"), p.authors());
        assertEquals(1240705L, p.fileSize());
        assertEquals("https://example.com/default.zip", p.url());
        assertTrue(p.downloadable());
        assertEquals(2, p.animations().size());
        assertEquals(java.util.List.of("dongzhangxiwang"), p.animations().get("idle"));
    }

    @Test
    @DisplayName("署名行由 pack 字段驱动（UI/命令/文档共用一份）")
    void attributionComesFromPack() {
        AssetPack p = AssetManifest.parse(GOOD).defaultPack();
        String attr = p.attribution();
        assertTrue(attr.contains("PC2005-cloud"), "须含作者");
        assertTrue(attr.contains("https://github.com/PC2005-cloud/dsh-pet"), "须含上游地址（二创署名要求）");
        assertTrue(attr.contains("NON-COMMERCIAL"), "须含禁止商用口径");
    }

    @Test
    @DisplayName("容错: 单条坏数据跳过，不拖垮整份清单")
    void badEntryIsSkipped() {
        String json = """
                { "manifestVersion": 1, "assets": [
                  { "id": "BAD ID!", "url": "https://x/y.zip" },
                  { "id": "ok", "url": "https://x/ok.zip" }
                ] }
                """;
        AssetManifest m = AssetManifest.parse(json);
        assertEquals(1, m.packs().size(), "坏条目应被跳过，好的保留");
        assertEquals("ok", m.packs().get(0).id());
    }

    @Test
    @DisplayName("容错: 缺字段用默认值（name/version/category/license），不抛")
    void missingFieldsFallBack() {
        AssetManifest m = AssetManifest.parse("""
                { "assets": [ { "id": "minimal", "url": "https://x/a.zip" } ] }
                """);
        AssetPack p = m.packs().get(0);
        assertEquals("minimal", p.name(), "缺 name ⇒ 回落 id");
        assertEquals("0", p.version());
        assertEquals(AssetPack.CATEGORY_MODEL, p.category());
        assertEquals("unknown", p.license(), "缺 license ⇒ unknown（不假装是 MIT）");
        assertEquals(0L, p.fileSize());
        assertEquals(AssetManifest.CURRENT_VERSION, m.manifestVersion(), "缺 manifestVersion ⇒ 视为当前");
    }

    @Test
    @DisplayName("防护: id 非法字符/路径穿越一律拒绝")
    void idIsPathSafe() {
        assertThrows(IllegalArgumentException.class, () -> pack("Bad Id"));
        assertThrows(IllegalArgumentException.class, () -> pack(".."));
        assertThrows(IllegalArgumentException.class, () -> pack("../etc"));
        assertThrows(IllegalArgumentException.class, () -> pack("a/b"));
        assertThrows(IllegalArgumentException.class, () -> pack(""));
        assertNotNull(pack("good-id_1.2"), "合法字符集 [a-z0-9._-] 应通过");
    }

    /** 用最小包构造来验证 id 校验（其余字段无关紧要）。 */
    private static AssetPack pack(String id) {
        return new AssetPack(id, null, null, null, null, null, null, null, 0L, null,
                "https://example.com/a.zip", null, null);
    }

    @Test
    @DisplayName("语法: JSON 解析覆盖 转义/嵌套/数字/布尔/null/空容器")
    void jsonSyntax() {
        Map<String, Object> m = AssetJson.parseObject("""
                {"s":"a\\"b\\\\c\\n","n":-12.5e2,"t":true,"f":false,"nil":null,"arr":[],"obj":{}}
                """);
        assertEquals("a\"b\\c\n", m.get("s"));
        assertEquals(-1250.0, m.get("n"));
        assertEquals(Boolean.TRUE, m.get("t"));
        assertEquals(Boolean.FALSE, m.get("f"));
        assertTrue(m.containsKey("nil"));
        assertEquals(0, AssetJson.objectList(m.get("arr")).size());
    }

    @Test
    @DisplayName("语法: 坏 JSON 必须抛（由调用方兜底，不静默）")
    void badJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> AssetJson.parseObject(""));
        assertThrows(IllegalArgumentException.class, () -> AssetJson.parseObject("{"));
        assertThrows(IllegalArgumentException.class, () -> AssetJson.parseObject("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> AssetJson.parseObject("[]"));
        assertThrows(IllegalArgumentException.class, () -> AssetJson.parseObject("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> AssetJson.parseObject("{\"a\":\"\\x\"}"));
    }

    @Test
    @DisplayName("取值助手: 类型不符返回默认值（不抛）")
    void coercionIsTolerant() {
        Map<String, Object> m = AssetJson.parseObject("""
                {"str":123,"num":"abc","list":"single","obj":5}
                """);
        assertEquals("", AssetJson.str(m, "str"), "数字当字符串 ⇒ 空串");
        assertEquals(0L, AssetJson.lng(m, "num"), "非数字字符串 ⇒ 0");
        assertEquals(java.util.List.of("single"), AssetJson.stringList(m.get("list")), "标量容错成单元素列表");
        assertTrue(AssetJson.stringListMap(m.get("obj")).isEmpty(), "非对象 ⇒ 空表");
    }

    @Test
    @DisplayName("清单版本门控: 高于支持版本 ⇒ supported()=false")
    void versionGate() {
        assertTrue(AssetManifest.parse("{\"manifestVersion\":1,\"assets\":[]}").supported());
        assertFalse(AssetManifest.parse("{\"manifestVersion\":99,\"assets\":[]}").supported());
    }

    @Test
    @DisplayName("空清单: byId/defaultPack 返回 null，不抛")
    void emptyManifest() {
        AssetManifest empty = AssetManifest.empty();
        assertEquals(0, empty.packs().size());
        assertNull(empty.defaultPack());
        assertNull(empty.byId("x"));
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }
}
