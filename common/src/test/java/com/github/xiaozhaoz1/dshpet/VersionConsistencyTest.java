package com.github.xiaozhaoz1.dshpet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号一致性守护（守则 §5.4：版本号多处同步）。
 *
 * <p><b>事实</b>：本项目的版本号是 {@code versions/&lt;mc&gt;/gradle.properties} 里的
 * {@code project.version=<mod 版本>+<mc 版本>}（如 {@code 0.1.0+1.20.1}），由 Stonecutter 用来拼产物名；
 * 而 {@link DshPet#VERSION} 与两个 {@code mods.toml} 各自写死 —— 共 **4 处**可能漂移。</p>
 *
 * <p>本测试校验这 4 处一致（{@code DshPet.VERSION} == 两个节点的 {@code +} 前缀 == 两个 toml 的 version）。</p>
 *
 * <p><b>为什么用系统属性而非相对路径</b>（#D11 教训）：首版相对路径找不到文件就 {@code return}
 * ⇒ 故意改错版本号仍是绿的（**恒绿空转**）。现在仓库根由 Gradle 注入
 * {@code -Ddshpet.repoRoot=...}，**找不到即 fail**。</p>
 */
class VersionConsistencyTest {

    private static final Pattern PROJECT_VERSION = Pattern.compile(
            "^project\\.version\\s*=\\s*(\\S+)\\s*$", Pattern.MULTILINE);
    private static final Pattern TOML_VERSION = Pattern.compile(
            "^version\\s*=\\s*\"([^\"]+)\"\\s*$", Pattern.MULTILINE);

    @Test
    @DisplayName("守护: DshPet.VERSION 与两个节点的 project.version 前缀一致")
    void modVersionMatchesNodeVersions() throws IOException {
        for (String node : new String[] {"1.20.1", "1.21.1"}) {
            Path props = repoRoot().resolve("versions").resolve(node).resolve("gradle.properties");
            assertTrue(Files.isRegularFile(props), () -> "缺少节点配置: " + props);
            String full = firstGroup(PROJECT_VERSION, Files.readString(props, StandardCharsets.UTF_8),
                    "project.version", props);
            String modPart = full.contains("+") ? full.substring(0, full.indexOf('+')) : full;
            assertEquals(DshPet.VERSION, modPart,
                    () -> node + " 的 project.version=" + full + " ⇒ mod 版本 " + modPart
                            + "，但 DshPet.VERSION=" + DshPet.VERSION);
        }
    }

    @Test
    @DisplayName("守护: 两个 mods.toml 的 version 与 DshPet.VERSION 一致")
    void modsTomlVersionsMatch() throws IOException {
        List<Path> tomls = new ArrayList<>();
        tomls.add(repoRoot().resolve("forge/src/main/resources/META-INF/mods.toml"));
        tomls.add(repoRoot().resolve("neoforge/src/main/resources/META-INF/neoforge.mods.toml"));
        for (Path toml : tomls) {
            assertTrue(Files.isRegularFile(toml), () -> "缺少元数据文件: " + toml);
            String v = firstGroup(TOML_VERSION, Files.readString(toml, StandardCharsets.UTF_8),
                    "version", toml);
            assertEquals(DshPet.VERSION, v, () -> toml.getFileName() + " 版本 " + v
                    + " 与 DshPet.VERSION=" + DshPet.VERSION + " 不一致");
        }
    }

    @Test
    @DisplayName("守护: MOD_ID 必须小写字母开头（MC 命名空间要求）")
    void modIdIsValid() {
        assertTrue(DshPet.MOD_ID.matches("[a-z][a-z0-9_]*"), () -> "MOD_ID 非法: " + DshPet.MOD_ID);
    }

    /** 取第一个捕获组；找不到即 fail（**不允许静默跳过**）。 */
    private static String firstGroup(Pattern pattern, String text, String what, Path where) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(), () -> where + " 中找不到 " + what);
        return m.group(1);
    }

    /**
     * 仓库根：Gradle 注入优先（{@code test { systemProperty 'dshpet.repoRoot', ... }}）；
     * 缺失时从当前目录向上搜；仍找不到 ⇒ **fail**（不静默）。
     */
    static Path repoRoot() {
        String injected = System.getProperty("dshpet.repoRoot");
        if (injected != null && !injected.isBlank()) {
            return Path.of(injected);
        }
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("versions"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("无法定位仓库根（应含 versions/ 目录）；"
                + "请确认 Gradle 注入了 -Ddshpet.repoRoot");
    }
}
