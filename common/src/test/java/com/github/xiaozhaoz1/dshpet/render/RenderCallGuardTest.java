package com.github.xiaozhaoz1.dshpet.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **渲染调用守护**（源码级）—— 专防"参数算对了但 API 用法错"这类**参数测试抓不到**的 bug。
 *
 * <p>来源：错题 #D14（2026-09-23 实机）。症状：日志里 `draw=47x60` 一切正常，
 * 但画面上只剩一小块碎片 —— 因为用了 7 参
 * {@code blit(RL,x,y,u,v,uW,vH)}，它的语义是"取纹理子矩形、**1:1 画出、不缩放**"，
 * 传按比例算好的 drawW/drawH 只会把**纹理左上角**画出来（角色大部分透明 ⇒ 只剩碎片）。
 * 正确做法是 11 参可缩放变体。</p>
 *
 * <p>为什么写成源码扫描：这两次调用的**参数个数**都合法、编译也过、`drawW/drawH` 的数值断言也过，
 * 只有"像素长什么样"能暴露它 —— 而单测看不见画面。源码形态是唯一可自动断言的抓手。</p>
 */
class RenderCallGuardTest {

    private static Path petHudSource() {
        String injected = System.getProperty("dshpet.repoRoot");
        Path root = injected != null && !injected.isBlank()
                ? Path.of(injected)
                : Path.of("").toAbsolutePath();
        return root.resolve("common/src/main/java/com/github/xiaozhaoz1/dshpet/render/PetHud.java");
    }

    @Test
    @DisplayName("守护: PetHud 只能用可缩放的 11 参 blit（7 参 blit 不缩放 ⇒ 实机只剩碎片）")
    void onlyScalingBlitIsUsed() throws Exception {
        Path src = petHudSource();
        assertTrue(Files.isRegularFile(src), () -> "找不到 PetHud 源码: " + src);
        String text = Files.readString(src, StandardCharsets.UTF_8);

        // ① 禁止 7 参"子矩形 1:1"形态：blit(tex, x, y, <数字/0>, <数字/0>, …) —— 前两个 u/v 为 0
        Pattern sevenArg = Pattern.compile("blit\\(\\s*tex\\s*,\\s*x\\s*,\\s*y\\s*,\\s*0\\s*,\\s*0\\s*,");
        Matcher m = sevenArg.matcher(text);
        assertFalse(m.find(),
                "发现 7 参 blit(0,0,uW,vH)：它不缩放，会把纹理左上角 1:1 画出来（错题 #D14）");

        // ② 必须存在 11 参缩放形态（宽高 + uOffset/vOffset + uW/vH + texW/texH = 11 个实参 ⇒ ≥10 个逗号）
        //    当前实现：graphics.blit(tex, 0, 0, physW, physH, 0f, 0f, physW, physH, physW, physH);
        Matcher call = Pattern.compile("blit\\(\\s*tex\\s*,\\s*([^;]*?)\\)\\s*;", Pattern.DOTALL)
                .matcher(text);
        boolean found11 = false;
        while (call.find()) {
            long commas = call.group(1).chars().filter(c -> c == ',').count();
            if (commas >= 9) {
                found11 = true;
                break;
            }
        }
        assertTrue(found11,
                "必须用 11 参可缩放 blit(tex, x, y, w, h, uOffset, vOffset, uW, vH, texW, texH)；"
                        + "7 参版本不缩放（错题 #D14）");
    }

    @Test
    @DisplayName("守护: 换帧必须显式 texture.upload()（setPixels 不上传 ⇒ 否则 gif 永远停在首帧）")
    void frameSwapMustUpload() throws Exception {
        String text = Files.readString(spriteBackendSource(), StandardCharsets.UTF_8);
        assertTrue(text.contains("setPixels("), "应通过 setPixels 换帧");
        assertTrue(text.contains(".upload()"),
                "setPixels 只换引用、不上传 GPU（DynamicTexture 源码实证）⇒ 必须显式 upload()，否则动画不动");
        // 顺序：setPixels 之后紧邻 upload（同处换帧分支）
        int setIdx = text.indexOf("setPixels(toNativeImage");
        int upIdx = text.indexOf(".upload()", setIdx);
        assertTrue(setIdx > 0 && upIdx > setIdx,
                "upload() 必须紧跟 setPixels 之后（换帧分支内）");
    }

    @Test
    @DisplayName("守护: 创建纹理时必须关闭线性过滤（否则像素风素材缩小渲染会发糊）")
    void textureFilterMustBeNearest() throws Exception {
        String text = Files.readString(spriteBackendSource(), StandardCharsets.UTF_8);
        assertTrue(text.contains("setFilter(false, false)"),
                "像素风素材需 GL_NEAREST（blur=false, mipmap=false），否则缩放后模糊（用户实机反馈）");
    }

    private static Path spriteBackendSource() {
        String injected = System.getProperty("dshpet.repoRoot");
        Path root = injected != null && !injected.isBlank()
                ? Path.of(injected)
                : Path.of("").toAbsolutePath();
        return root.resolve("common/src/main/java/com/github/xiaozhaoz1/dshpet/render/SpriteBackend.java");
    }

    // ── 自研 Screen 的渲染顺序守护（错题 #D17） ─────────────────────────────

    @Test
    @DisplayName("守护: 自研 Screen 不得自己调 renderBackground（会被 super.render 再模糊一次 ⇒ 文字糊、按钮亮）")
    void screenMustNotCallRenderBackgroundItself() throws Exception {
        for (String rel : new String[] {
                "common/src/main/java/com/github/xiaozhaoz1/dshpet/screen/DshPetConfigScreen.java",
                "common/src/main/java/com/github/xiaozhaoz1/dshpet/screen/AssetDownloadScreen.java"}) {
            Path f = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(f), () -> "找不到 " + f);
            String text = Files.readString(f, StandardCharsets.UTF_8);
            // 允许注释里提到该方法名，但不得出现真实调用「renderBackground(」
            long calls = text.lines()
                    .filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//"))
                    .filter(l -> l.contains("renderBackground("))
                    .count();
            assertTrue(calls == 0,
                    () -> rel + " 出现了 renderBackground( 调用：Screen.render 内部已调一次，"
                            + "自己再调会把已绘制的文字一起模糊（错题 #D17）");
        }
    }

    @Test
    @DisplayName("守护: 自研 Screen 的自绘文字必须写在 super.render 之后（否则被背景模糊掉）")
    void customTextMustComeAfterSuperRender() throws Exception {
        for (String rel : new String[] {
                "common/src/main/java/com/github/xiaozhaoz1/dshpet/screen/DshPetConfigScreen.java",
                "common/src/main/java/com/github/xiaozhaoz1/dshpet/screen/AssetDownloadScreen.java"}) {
            Path f = repoRoot().resolve(rel);
            assertTrue(Files.isRegularFile(f), () -> "找不到 " + f);
            String text = Files.readString(f, StandardCharsets.UTF_8);
            int renderIdx = text.indexOf("public void render(GuiGraphics");
            assertTrue(renderIdx > 0, () -> rel + " 应有 render 覆写");
            int superIdx = text.indexOf("super.render(", renderIdx);
            assertTrue(superIdx > renderIdx, () -> rel + " 的 render 中应调用 super.render");
            // super 之后必须出现 drawString（自绘文字）
            int drawIdx = text.indexOf("drawString(", superIdx);
            assertTrue(drawIdx > superIdx,
                    () -> rel + " 的自绘文字（drawString）必须在 super.render 之后，否则会被背景模糊（错题 #D17）");
        }
    }

    private static Path repoRoot() {
        String injected = System.getProperty("dshpet.repoRoot");
        return injected != null && !injected.isBlank()
                ? Path.of(injected) : Path.of("").toAbsolutePath();
    }
}
