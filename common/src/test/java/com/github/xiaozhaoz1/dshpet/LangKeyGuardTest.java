package com.github.xiaozhaoz1.dshpet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * lang 键对齐守护 —— 中英语言文件**键集必须一致**（缺键 ⇒ 另一语言显示原始键名，用户体验破损）。
 *
 * <p>路径定位与 {@code VersionConsistencyTest} 同款：构建脚本注入 {@code dshpet.repoRoot}；
 * 找不到即 fail（不静默跳过 —— #D6/#D11 教训）。</p>
 */
class LangKeyGuardTest {

    private static final Pattern KEY = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:", Pattern.MULTILINE);

    private static Path langDir() {
        String injected = System.getProperty("dshpet.repoRoot");
        Path root = injected != null && !injected.isBlank()
                ? Path.of(injected)
                : Path.of("").toAbsolutePath();
        return root.resolve("common/src/main/resources/assets/dshpet/lang");
    }

    @Test
    @DisplayName("守护: zh_cn 与 en_us 的键集必须完全一致（双向差集为空）")
    void langKeysAreAligned() throws Exception {
        Set<String> zh = keys("zh_cn.json");
        Set<String> en = keys("en_us.json");
        assertTrue(!zh.isEmpty(), "zh_cn.json 不应为空");

        Set<String> missingInEn = new LinkedHashSet<>(zh);
        missingInEn.removeAll(en);
        Set<String> missingInZh = new LinkedHashSet<>(en);
        missingInZh.removeAll(zh);

        assertTrue(missingInEn.isEmpty(), () -> "en_us 缺少键: " + missingInEn);
        assertTrue(missingInZh.isEmpty(), () -> "zh_cn 缺少键: " + missingInZh);
    }

    @Test
    @DisplayName("守护: 命令/界面里用到的 lang 键必须都在语言文件中（防漏加键）")
    void usedKeysExist() throws Exception {
        Set<String> zh = keys("zh_cn.json");
        Set<String> used = collectUsedKeys();
        assertTrue(!used.isEmpty(), "应能扫到源码中的 lang 键（否则扫描逻辑失效）");
        Set<String> missing = new LinkedHashSet<>();
        for (String k : used) {
            if (!zh.contains(k)) {
                missing.add(k);
            }
        }
        assertTrue(missing.isEmpty(), () -> "语言文件缺少被引用的键: " + missing);
    }

    private static Set<String> keys(String fileName) throws Exception {
        Path f = langDir().resolve(fileName);
        assertTrue(Files.isRegularFile(f), () -> "找不到语言文件: " + f);
        Set<String> out = new LinkedHashSet<>();
        Matcher m = KEY.matcher(Files.readString(f, StandardCharsets.UTF_8));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 扫描源码里 {@code translatable("...")} 的键。 */
    private static Set<String> collectUsedKeys() throws Exception {
        Path src = langDir().getParent().getParent().getParent().getParent().getParent();
        // .../common/src/main/resources/assets/dshpet/lang → 上溯到仓库根
        String injected = System.getProperty("dshpet.repoRoot");
        Path root = injected != null && !injected.isBlank() ? Path.of(injected) : src;
        Path javaRoot = root.resolve("common/src/main/java");
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isDirectory(javaRoot)) {
            return out;
        }
        Pattern p = Pattern.compile("translatable\\(\\s*\"([a-z0-9_.]+)\"");
        try (var walk = Files.walk(javaRoot)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = p.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }
}
