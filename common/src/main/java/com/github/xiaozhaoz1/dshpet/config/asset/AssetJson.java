package com.github.xiaozhaoz1.dshpet.config.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析 + 取值助手（**自持实现，零第三方依赖**）。
 *
 * <p>为什么自己写（2026-09-23 取舍，记录理由）：本项目只需读**自己托管的清单**这一个结构化文件，
 * 内容受控、schema 简单；为此引入 Gson/Jackson（jar-in-jar、体积、许可、版本漂移）
 * 违反"不为零/单一调用方建面"。同时**不使用正则**（清单含嵌套数组，正则方案在本会话已因转义踩坑多次）。</p>
 *
 * <p>职责边界：本类只负责<b>语法</b>（解析成 {@code Map/List/String/Double/Boolean/null}）与
 * <b>类型取值</b>（缺失/类型不符 ⇒ 返回默认值，不抛异常）。<b>语义校验</b>（id 白名单、必填字段）
 * 由 {@link AssetPack} 与调用方负责 —— 即 parse-don't-validate 的分工。</p>
 *
 * <p>失败语义：语法错误抛 {@link IllegalArgumentException}（由调用方 {@code catch Throwable} 兜底，
 * 见错题 #335 同族：不静默吞掉）。</p>
 */
public final class AssetJson {

    private final String src;
    private int pos;

    private AssetJson(String src) {
        this.src = src;
    }

    /** 解析 JSON 文本为 {@code Map<String,Object>}（顶层必须是对象）。 */
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("JSON 文本为空");
        }
        AssetJson p = new AssetJson(text);
        p.skipWs();
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new IllegalArgumentException("JSON 尾部有多余内容 @" + p.pos);
        }
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON 顶层必须是对象，实际: " + v.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) v;
        return out;
    }

    // ── 取值助手（类型不符/缺失 ⇒ 默认值，不抛） ────────────────────────────────

    public static String str(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        return v instanceof String s ? s : "";
    }

    public static long lng(Map<String, Object> raw, String key) {
        Object v = raw.get(key);
        if (v instanceof Double d) {
            return d.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /** 字符串数组；元素非字符串则跳过（容错优先，不整体失败）。 */
    public static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
        } else if (v instanceof String s && !s.isBlank()) {
            out.add(s); // 容忍单值写成标量
        }
        return out;
    }

    /** {@code Map<String, List<String>>}；非列表值跳过。 */
    public static Map<String, List<String>> stringListMap(Object v) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String k) {
                    List<String> vals = stringList(e.getValue());
                    if (!vals.isEmpty()) {
                        out.put(k, vals);
                    }
                }
            }
        }
        return out;
    }

    /** 对象列表（清单的 {@code assets} 数组）。 */
    public static List<Map<String, Object>> objectList(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object e : list) {
                if (e instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, val) -> {
                        if (k instanceof String ks) {
                            row.put(ks, val);
                        }
                    });
                    out.add(row);
                }
            }
        }
        return out;
    }

    // ── 语法解析 ───────────────────────────────────────────────────────────

    private Object parseValue() {
        skipWs();
        char c = peek();
        return switch (c) {
            case '{' -> parseObj();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObj() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = parseString();
            skipWs();
            expect(':');
            Object value = parseValue();
            map.put(key, value);
            skipWs();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw new IllegalArgumentException("对象内期望 ',' 或 '}'，实际 '" + c + "' @" + (pos - 1));
            }
        }
    }

    private List<Object> parseArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(parseValue());
            skipWs();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw new IllegalArgumentException("数组内期望 ',' 或 ']'，实际 '" + c + "' @" + (pos - 1));
            }
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = next();
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw new IllegalArgumentException("\\u 转义不完整 @" + pos);
                    }
                    String hex = src.substring(pos, pos + 4);
                    pos += 4;
                    sb.append((char) Integer.parseInt(hex, 16));
                }
                default -> throw new IllegalArgumentException("非法转义 \\" + esc + " @" + (pos - 1));
            }
        }
    }

    private Double parseNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw new IllegalArgumentException("期望数值 @" + pos);
        }
        String num = src.substring(start, pos);
        try {
            return Double.valueOf(num);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法数值 '" + num + "' @" + start);
        }
    }

    private Object parseLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw new IllegalArgumentException("期望 " + literal + " @" + pos);
        }
        pos += literal.length();
        return value;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new IllegalArgumentException("JSON 意外结束 @" + pos);
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        char actual = next();
        if (actual != c) {
            throw new IllegalArgumentException("期望 '" + c + "'，实际 '" + actual + "' @" + (pos - 1));
        }
    }
}
