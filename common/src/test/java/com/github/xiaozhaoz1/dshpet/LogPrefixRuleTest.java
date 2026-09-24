package com.github.xiaozhaoz1.dshpet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护正则的**自证测试**（PROJECT-RULES：新增校验方法必须先用"已知违规 + 已知正确"样本各测一次，
 * 证明它真的会红/会绿，而不是恒绿空转）。
 *
 * <p>本文件、{@link DshPetLogGuardTest} 与生产代码共享同一条正则规则，改动需同步三处。</p>
 */
class LogPrefixRuleTest {

    /**
     * 匹配日志前缀字面量（含开括号、不含闭括号 —— 这是 Pattern 的既有行为，勿"修正"）。
     *
     * <p>正则要点：字符类里的连字符必须**转义**（{@code \\-}），否则 {@code 9_/} 会被当作范围。</p>
     */
    static final Pattern RULE = Pattern.compile("\"\\[(?:DEBUG-)?[A-Za-z][A-Za-z0-9_/\\-]*");

    /** 从源码片段中提取规范前缀（两侧方括号补齐；无匹配返回 null）。 */
    static String prefixOf(String sourceLiteral) {
        Matcher m = RULE.matcher(sourceLiteral);
        if (!m.find()) {
            return null;
        }
        String raw = m.group();                      // 形如 "[DSHPET/PET  → 含引号+开括号
        String inner = raw.substring(2);             // 去掉前导 '"' 与 '['
        return "[" + inner + "]";                    // 补齐闭括号
    }

    private static boolean isAllowed(String sourceLiteral) {
        String p = prefixOf(sourceLiteral);
        return p != null && List.of(DshPetLog.ALL_PREFIXES).contains(p);
    }

    @Test
    @DisplayName("自证-已知正确样本：canonical 表内前缀必须通过（证明守护不误报）")
    void knownGoodAreAccepted() {
        assertTrue(isAllowed("\"" + DshPetLog.PET + " 加载完成\""), "PET 前缀应通过");
        assertTrue(isAllowed("\"" + DshPetLog.CFG + " 读取失败\""), "CFG 前缀应通过");
        assertTrue(isAllowed("\"" + DshPetLog.CMD + " 已切换\""), "CMD 前缀应通过");
        assertTrue(isAllowed("\"" + DshPetLog.ASSET + " 回退内置\""), "ASSET 前缀应通过");
        assertTrue(isAllowed("\"" + DshPetLog.DEBUG + " screen=480x255\""), "DEBUG 前缀应通过");
    }

    @Test
    @DisplayName("自证-已知违规样本：表外前缀必须判违规（证明守护会红）")
    void knownViolationsAreRejected() {
        assertFalse(isAllowed("\"[dshpet] 旧前缀写法\""), "[dshpet] 应为违规");
        assertFalse(isAllowed("\"[OTHER] 别的 mod 风格\""), "[OTHER] 应为违规");
        assertFalse(isAllowed("\"[DSHPET] 缺子系统\""), "[DSHPET] 应为违规");
        assertFalse(isAllowed("\"nope 没有前缀\""), "无前缀应返回 null ⇒ 不算允许");
    }

    @Test
    @DisplayName("自证-非日志字符串不受影响（不误伤路径/格式串/普通文案）")
    void normalStringsUnmatched() {
        assertFalse(isAllowed("\"config/dshpet.toml\""), "路径不应被当作日志前缀");
        assertFalse(isAllowed("\"{} 帧 / {} fps\""), "格式串不应被当作日志前缀");
        assertFalse(isAllowed("\"已加载动画 '{}'\""), "纯文本不应被当作日志前缀");
    }

    @Test
    @DisplayName("自证-归一化函数本身正确")
    void prefixNormalisationIsCorrect() {
        assertEquals("[DSHPET/PET]", prefixOf("\"[DSHPET/PET] 文案\""));
        assertEquals("[DEBUG-DSHPET]", prefixOf("\"[DEBUG-DSHPET] screen=1x1\""));
        assertNull(prefixOf("\"普通文案\""));
    }
}
