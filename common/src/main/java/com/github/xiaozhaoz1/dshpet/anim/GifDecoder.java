package com.github.xiaozhaoz1.dshpet.anim;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 图片解码器（GIF 动图 / PNG 静态图）—— 解成 {@link AnimationClip}（RGBA 帧）。
 *
 * <p>PNG 支持是刻意保留的：① 用户自制静态图零门槛；② 诊断标尺图用 PNG 最方便。</p>
 *
 * <p><b>实测依据</b>（本机 Java 21 + ImageIO，见 docs/AUDIT-2026-09-22.md §八）：
 * <ul>
 *   <li>上游 GIF 每帧是完整子矩形（72×92），{@code reader.read(i)} <b>直接得完整帧，无需手工合成</b>；</li>
 *   <li>全 120 帧解码 65–100 ms（0.54–0.83 ms/帧）；二次读<b>不便宜</b> ⇒ 整条解码后缓存；</li>
 *   <li>单帧 26 KB / 整条 3 MB ⇒ 惰性加载，绝不预解全量。</li>
 * </ul>
 *
 * <p><b>资源纪律（硬约束 C4）</b>：{@link ImageInputStream} / {@link ImageReader} 用完即关。
 * Windows 下未关闭的流会锁住用户目录里的 GIF 文件，导致用户无法编辑/删除自己的素材。</p>
 */
public final class GifDecoder {

    private GifDecoder() {
    }

    /** 扫描阶段用的轻量元数据（不解码像素）。 */
    public record Info(int width, int height, int frameCount, double fps) {
    }

    /**
     * 只读文件头拿元数据（廉价：不解码帧数据）—— 供"惰性解帧"的扫描阶段使用。
     *
     * @throws IOException 打不开/非 GIF/无帧时抛出（调用方据 §2.1 契约记 WARN 并回退下一层来源）
     */
    public static Info readInfo(InputStream in) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                throw new IOException("cannot create ImageInputStream (null)");
            }
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                throw new IOException("no ImageIO reader (unsupported format?)");
            }
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, false, false);
                // 接受 ImageIO 能识别的任意格式 (gif 动图 / png 静态图) —— 诊断与用户自制都受益
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                int n = reader.getNumImages(true);
                if (n <= 0) {
                    throw new IOException("image has no frames");
                }
                return new Info(w, h, n, detectFps(reader, n));
            } finally {
                reader.dispose(); // 必须: 否则 reader 持有流的引用
            }
        }
    }

    /**
     * 完整解码一条 GIF 为 RGBA 帧序列。
     *
     * @throws IOException 解码失败（调用方按 §2.1 契约回退）
     */
    public static AnimationClip decode(InputStream in, String name, AnimationClip.Source source) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
            if (!it.hasNext()) {
                throw new IOException("no ImageIO reader (unsupported format?)");
            }
            ImageReader reader = it.next();
            try {
                reader.setInput(iis, false, false);
                int n = reader.getNumImages(true);
                if (n <= 0) {
                    throw new IOException("image has no frames");
                }
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                double fps = detectFps(reader, n);

                List<byte[]> frames = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    BufferedImage img = reader.read(i);
                    if (img == null) {
                        throw new IOException("frame " + i + " decoded to null");
                    }
                    frames.add(toRgba(img, w, h));
                }
                return new AnimationClip(name, source, w, h, fps, frames);
            } finally {
                reader.dispose();
            }
        }
    }

    /** BufferedImage → RGBA8 字节数组（行主序）。尺寸与画布不符时按左上对齐绘制到透明画布。 */
    private static byte[] toRgba(BufferedImage src, int canvasW, int canvasH) {
        BufferedImage rgba;
        if (src.getWidth() == canvasW && src.getHeight() == canvasH
                && src.getType() == BufferedImage.TYPE_INT_ARGB) {
            rgba = src;
        } else {
            rgba = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            var g = rgba.createGraphics();
            try {
                g.drawImage(src, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        int[] argb = rgba.getRGB(0, 0, canvasW, canvasH, null, 0, canvasW);
        byte[] out = new byte[canvasW * canvasH * 4];
        for (int i = 0, o = 0; i < argb.length; i++) {
            int c = argb[i];
            out[o++] = (byte) ((c >>> 16) & 0xFF); // R
            out[o++] = (byte) ((c >>> 8) & 0xFF);  // G
            out[o++] = (byte) (c & 0xFF);          // B
            out[o++] = (byte) ((c >>> 24) & 0xFF); // A
        }
        return out;
    }

    /**
     * 由 GIF 每帧延时推导 fps（取首帧延时，缺失/非法则用 {@link AnimationClip#DEFAULT_FPS}）。
     * 上游实测延时 8/9 cs 交替 ⇒ ≈12 fps。
     */
    private static double detectFps(ImageReader reader, int frameCount) {
        try {
            IIOMetadata md = reader.getImageMetadata(0);
            if (md == null) {
                return AnimationClip.DEFAULT_FPS;
            }
            org.w3c.dom.Node root = md.getAsTree("javax_imageio_gif_image_1.0");
            org.w3c.dom.NodeList gce = ((org.w3c.dom.Element) root)
                    .getElementsByTagName("GraphicControlExtension");
            if (gce.getLength() > 0) {
                String delayCs = ((org.w3c.dom.Element) gce.item(0)).getAttribute("delayTime");
                int cs = Integer.parseInt(delayCs.trim());
                if (cs > 0) {
                    return 100.0 / cs; // 1 cs = 10 ms ⇒ fps = 1000/(cs*10)
                }
            }
        } catch (Throwable ignored) {
            // 元数据解析失败不致命: 用兜底帧率 (渲染仍可用, 只是速度可能不准)
        }
        return AnimationClip.DEFAULT_FPS;
    }
}
