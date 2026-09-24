package com.github.xiaozhaoz1.dshpet.render;

import com.github.xiaozhaoz1.dshpet.DshPetLog;
import com.github.xiaozhaoz1.dshpet.anim.AnimationClip;
import com.github.xiaozhaoz1.dshpet.anim.GifDecoder;
import com.github.xiaozhaoz1.dshpet.config.asset.AssetStore;
import com.github.xiaozhaoz1.dshpet.core.ImageResampler;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 2D 精灵渲染后端（M1 版）：GIF 帧 → NativeImage → DynamicTexture → 缓存复用。
 *
 * <p><b>实测背书</b>（docs/AUDIT-2026-09-22.md §八）：
 * <ul>
 *   <li>「每动画复用 1 个 DynamicTexture + setPixels」—— 100,000 次 new→写像素→close 循环
 *       理论累计 2,527 MB，实测 committed 净增长 <b>0.0 MB</b>（无堆外泄漏）；</li>
 *   <li>线程纪律：解码可在异步线程，但纹理创建/上传/释放<b>必须回主线程</b>
 *       （{@code AbstractTexture.getId()} = assertOnRenderThreadOrInit，两版一致）；</li>
 *   <li>绘制用 7 参 {@code blit(ResourceLocation,x,y,u,v,w,h)}——两版签名一致，避免 1.21.1 独有的 5 参重载。</li>
 * </ul>
 */
public final class SpriteBackend {

    /** 帧纹理缓存：动画名 → 已上传纹理。每动画仅 1 张，切帧走 setPixels 复用。 */
    private static final Map<String, Entry> CACHE = new HashMap<>();

    private static int nextId = 1;

    private SpriteBackend() {
    }

    private static final class Entry {
        final AnimationClip clip;
        final DynamicTexture texture;
        final ResourceLocation location;
        int uploadedFrame = -1;
        /** 当前纹理的物理尺寸（尺寸变化需重建）。 */
        final int width;
        final int height;

        Entry(AnimationClip clip, DynamicTexture texture, ResourceLocation location,
              int width, int height) {
            this.clip = clip;
            this.texture = texture;
            this.location = location;
            this.width = width;
            this.height = height;
        }
    }

    /** 确保纹理就绪并把指定帧上传（**必须在主线程调用**）。幂等：同一帧重复调用不重传。 */
    /**
     * 取（或构建）某帧纹理 —— **方案 B：CPU 预缩放到目标物理尺寸，供 1:1 绘制**。
     *
     * <p>为什么要目标尺寸参数（2026-09-23 用户裁定方案 B）：素材 72×92，显示尺寸随窗口变化，
     * "每 texel 占的物理像素数"几乎总不是整数 ⇒ 交给 GPU 缩放必然要么糊（线性）要么抖（最近邻）。
     * 这里在 CPU 上按 {@code ImageResampler} 重采样到**恰好** {@code physW×physH}，
     * 之后绘制端 1:1 贴出 ⇒ GPU 不做二次采样 ⇒ 清晰度可控且尺寸自由。</p>
     *
     * <p>缓存键含尺寸：窗口/guiScale 变化 ⇒ 自动重建（旧纹理释放，不泄漏）。</p>
     *
     * @param clip  动画
     * @param frame 帧索引
     * @param physW 目标物理宽（≤0 表示按素材原尺寸）
     * @param physH 目标物理高
     * @param mode  重采样核
     */
    public static ResourceLocation textureFor(AnimationClip clip, int frame,
                                             int physW, int physH, ImageResampler.Mode mode) {
        int tw = physW > 0 ? physW : clip.width();
        int th = physH > 0 ? physH : clip.height();
        Entry e = CACHE.get(clip.name());
        // 尺寸变化（窗口缩放/guiScale 变）⇒ 重建纹理
        if (e != null && (e.width != tw || e.height != th)) {
            release(clip.name());
            e = null;
        }
        if (e == null) {
            NativeImage img = toNativeImage(clip, frame, tw, th, mode);
            DynamicTexture tex = new DynamicTexture(img); // 构造器内部已 prepareImage + upload
            // 已预缩放到目标物理尺寸、且 1:1 绘制 ⇒ 关闭线性过滤，杜绝任何残余插值（用户实机反馈"有点模糊"）
            tex.setFilter(false, false);
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                    "dshpet", "anim/" + safe(clip.name()) + "_" + nextId++);
            Minecraft.getInstance().getTextureManager().register(loc, tex);
            e = new Entry(clip, tex, loc, tw, th);
            e.uploadedFrame = frame;
            CACHE.put(clip.name(), e);
            return loc;
        }
        if (e.uploadedFrame != frame) {
            // ⚠️ 换帧必须【显式 upload()】（2026-09-23 实机 bug，错题 #D15）：
            //   DynamicTexture.setPixels(NativeImage) 的语义只是"close 旧图 + 换引用"，
            //   **不会**把新像素传到 GPU（1.21.1 源码实证）⇒ 只调 setPixels 画面会永远停在首帧（"gif 不动"）。
            e.texture.setPixels(toNativeImage(clip, frame, e.width, e.height, mode));
            e.texture.upload();
            e.uploadedFrame = frame;
        }
        return e.location;
    }

    /** 兼容旧签名（不重采样，按素材原尺寸）—— 供内部/测试与非缩放场景使用。 */
    public static ResourceLocation textureFor(AnimationClip clip, int frame) {
        return textureFor(clip, frame, clip.width(), clip.height(), ImageResampler.Mode.NEAREST);
    }

    /** 释放某动画的纹理（热重载/退出时调用；**主线程**）。 */
    public static void release(String name) {
        Entry e = CACHE.remove(name);
        if (e != null) {
            TextureManager tm = Minecraft.getInstance().getTextureManager();
            e.texture.close();
            tm.release(e.location);
        }
    }

    /** 释放全部（世界卸载/断开连接时调用）。 */
    public static void releaseAll() {
        for (Entry e : CACHE.values()) {
            e.texture.close();
            Minecraft.getInstance().getTextureManager().release(e.location);
        }
        CACHE.clear();
    }

    // ── 素材加载（三级：用户目录 → 当前启用包 → jar 内置；失败逐级回退） ─────────

    /**
     * 按名加载动画（**三级回退**，每级失败记 WARN 后继续下一级，绝不整条消失）：
     * <ol>
     *   <li>{@code config/dshpet/animations/}（用户手放，最高优先）</li>
     *   <li>{@code config/dshpet/assets/<当前启用包>/animations/}（下载的素材包）</li>
     *   <li>jar 内置 {@code assets/dshpet/animations/}（仅剩自制素材）</li>
     * </ol>
     *
     * @param userDir 用户动画目录（加载顺序第 ① 级；可为 null）
     * @return 解码成功的动画；全失败返回 {@code null}（调用方按"无素材"处理，不崩）
     */
    public static AnimationClip load(String name, Path userDir) {
        // ① 用户目录
        AnimationClip fromUser = tryLoadFromDir(userDir, name, AnimationClip.Source.USER, "用户目录");
        if (fromUser != null) {
            return fromUser;
        }
        // ② 当前启用的素材包
        Path packDir = AssetStore.activeAnimationsDir();
        if (packDir != null) {
            AnimationClip fromPack = tryLoadFromDir(packDir, name, AnimationClip.Source.USER, "素材包");
            if (fromPack != null) {
                return fromPack;
            }
        }
        // ③ jar 内置
        for (String ext : RESOURCE_EXTS) {
            try (InputStream in = SpriteBackend.class.getResourceAsStream(
                    "/assets/dshpet/animations/" + safe(name) + ext)) {
                if (in == null) {
                    continue;
                }
                return GifDecoder.decode(in, name, AnimationClip.Source.BUILTIN);
            } catch (Throwable t) {
                DshPetLog.warn(DshPetLog.ASSET, "内置动画 '{}' 解码失败({})", name, t.toString());
                return null;
            }
        }
        return null;
    }

    /**
     * 从某目录按扩展名优先级尝试解码（.gif → .png）。
     *
     * @return 成功返回动画；目录不存在/文件不存在返回 null（**不打日志**，属正常"没放"）；
     *         文件存在但解码失败 ⇒ WARN 后返回 null（由调用方继续下一级）
     */
    private static AnimationClip tryLoadFromDir(Path dir, String name, AnimationClip.Source source,
                                                String levelLabel) {
        if (dir == null) {
            return null;
        }
        Path f = firstExisting(dir, name);
        if (f == null) {
            return null;
        }
        try (InputStream in = Files.newInputStream(f)) {
            return GifDecoder.decode(in, name, source);
        } catch (Throwable t) { // 必须 Throwable: 畸形文件可能抛 Error（错题 #335 教训 3）
            DshPetLog.warn(DshPetLog.ASSET, "{} 的动画 '{}' 解码失败({})，继续回退下一级",
                    levelLabel, name, t.toString());
            return null;
        }
    }

    /** 支持的扩展名（顺序即优先级）：动图优先，静态图次之。 */
    private static final String[] RESOURCE_EXTS = {".gif", ".png"};

    /** 用户目录里按优先级找存在的文件（.gif → .png）。 */
    private static Path firstExisting(Path dir, String name) {
        for (String ext : RESOURCE_EXTS) {
            Path f = dir.resolve(name + ext);
            if (Files.isRegularFile(f)) {
                return f;
            }
        }
        return null;
    }

    /** 动画名 → 安全的资源路径片段（防目录穿越/非法字符；素材名本身已确认全 ASCII）。 */
    private static String safe(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    /** RGBA 字节 → NativeImage。注意 MC 的 {@code setPixelRGBA} 是 <b>ABGR</b>（小端）语义。 */
    /**
     * 帧 → NativeImage（**先 CPU 重采样到目标尺寸**，再按 ABGR 打包）。
     *
     * <p>重采样在 CPU 做（方案 B 的核心）：GPU 只负责 1:1 贴图，不再插值。</p>
     */
    private static NativeImage toNativeImage(AnimationClip clip, int frame,
                                             int w, int h, ImageResampler.Mode mode) {
        byte[] rgba = clip.frame(frame);
        if (w != clip.width() || h != clip.height()) {
            byte[] scaled = ImageResampler.resample(rgba, clip.width(), clip.height(), w, h, mode);
            if (scaled != null) {
                rgba = scaled; // 重采样失败则回退原尺寸（下面按实际来源尺寸打包）
            } else {
                w = clip.width();
                h = clip.height();
            }
        }
        NativeImage img = new NativeImage(w, h, false);
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = rgba[i] & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                int a = rgba[i + 3] & 0xFF;
                i += 4;
                img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r); // ABGR
            }
        }
        return img;
    }
}
