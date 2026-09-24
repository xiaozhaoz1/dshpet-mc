package com.github.xiaozhaoz1.dshpet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 日志前缀守护测试 —— 纯 JVM，不碰 MC 类型。
 *
 * <p>铁律与社区惯例（与 LMA 同款裁定）：日志前缀必须来自 {@link DshPetLog} 的 canonical 表；
 * <b>源码里出现表外的日志前缀字面量即判失败</b>。</p>
 *
 * <p>规则来源：{@link LogPrefixRuleTest#RULE}（同一正则，改动需同步；自证测试证明它既不误报也不空转）。
 * 只匹配带引号的字面量，故 javadoc 中 {@code [XXX]} 形式不受影响。</p>
 */
class DshPetLogGuardTest {

    @Test
    @DisplayName("守护: 源码中不得出现 DshPetLog.ALL_PREFIXES 之外的日志前缀字面量")
    void noAdHocLogPrefixes() throws Exception {
        Path srcRoot = Path.of("src/main/java");
        if (!Files.isDirectory(srcRoot)) {
            return; // 无源码目录（例如仅跑 jar）时跳过，不误报
        }
        List<String> allowed = new ArrayList<>(List.of(DshPetLog.ALL_PREFIXES));
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(srcRoot)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = LogPrefixRuleTest.RULE.matcher(text);
                while (m.find()) {
                    String normalized = "[" + m.group().substring(2) + "]";
                    if (!allowed.contains(normalized)) {
                        violations.add(f + " -> " + normalized);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "发现表外日志前缀（必须改用 DshPetLog 常量）:\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("守护: canonical 表非空且格式合法（全大写 + 含 modid）")
    void canonicalPrefixTableIsSane() {
        assertTrue(DshPetLog.ALL_PREFIXES.length > 0, "前缀表不得为空");
        for (String p : DshPetLog.ALL_PREFIXES) {
            assertTrue(p.startsWith("[") && p.endsWith("]"), "前缀必须被方括号包裹: " + p);
            String inner = p.substring(1, p.length() - 1);
            assertTrue(inner.equals(inner.toUpperCase(Locale.ROOT)),
                    "前缀必须全大写（canonical 约定）: " + p);
            assertTrue(inner.contains("DSHPET"), "前缀必须含 modid 便于 grep: " + p);
        }
    }
}
