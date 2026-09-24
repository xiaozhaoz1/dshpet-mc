# anim — 动画解码与数据模型

**作用**：把磁盘/jar 里的素材（GIF 动图、PNG 静态图）解成**平台无关的帧数据**（RGBA 字节数组）+ 元数据，供 `render/` 上传成纹理。

**判据（什么进这里）**：素材 → 帧序列的**解码与容器**；不含渲染、不含 MC 纹理（那是 `render/`）。

**依赖方向**：依赖 `core`（几何/时间轴）与 JDK 的 `javax.imageio`；**被 `render/` 依赖**。**不 import 任何 MC 类型**（当前 `GifDecoder` 只依赖 ImageIO ⇒ 可 JVM 单测）。

**类明细（2 类）**

| 类 | 职责 | 关键 API |
|---|---|---|
| `AnimationClip` | 一条动画的**不可变**快照：名字/来源/画布尺寸/fps/帧数据 | `record AnimationClip(name, source, w, h, fps, frames)` · `frame(i)`（越界钳制） · `bytesPerFrame()` · `Source{BUILTIN,USER}` |
| `GifDecoder` | 解码入口：**轻量读元数据**（扫描阶段）与**整条解码**（首次播放） | `readInfo(in)` → `Info(w,h,frameCount,fps)` · `decode(in,name,source)` → `AnimationClip` |

**支持的格式**：`.gif`（动图，多帧）与 `.png`（静态图，单帧）—— 由 ImageIO 原生支持，**零额外依赖**（不引 Gson/WebP 库）。

**关键设计（惰性解帧，见 docs/PLAN.md §3.1）**

```
扫描阶段：只 readInfo（读文件头拿帧数/尺寸，不解码像素）  → 启动开销 ≈ 0
首次播放：decode 整条（实测 65–100 ms / 120 帧）并缓存
切换动画：已缓存则复用；未缓存才解码
```
理由（实测）：单帧 26KB、整条 3MB、全 106 条 ≈ 321MB ⇒ **绝不预解全量**。

**已知陷阱**

| # | 陷阱 | 规则 |
|---|---|---|
| A | **资源泄漏会锁住用户文件**（Windows） | `ImageInputStream`/`ImageReader` 必须 `close()`/`dispose()`（`try-with-resources` + `finally dispose`）；否则用户无法编辑自己的 GIF |
| B | 帧尺寸可能与画布不同（子矩形） | 解码时按画布对齐绘制（左上对齐），不要假设"每帧尺寸=画布尺寸" |
| C | 畸形文件可能抛 `Error` 而非 `Exception` | 调用方的回退链必须 `catch (Throwable)`（见 `render/SpriteBackend`，错题 #335 同族） |
| D | fps 推导 | 从 GIF `GraphicControlExtension.delayTime`（单位 cs）推导；缺失/非法回落 `DEFAULT_FPS=12` |

**修改注意**：① 新增格式（如 APNG/WebP）前先确认 ImageIO 是否原生支持，否则引入依赖需用户裁定；② `AnimationClip` 是 record + 紧凑构造器校验，**改字段要同步所有构造点与单测**。
