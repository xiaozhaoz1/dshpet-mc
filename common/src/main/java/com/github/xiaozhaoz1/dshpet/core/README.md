# core — 纯逻辑层（零 MC 依赖）

**作用**：与 Minecraft **完全无关**的纯计算。放这里的东西必须能在**无游戏环境**下跑 JVM 单测 —— 这是本项目唯一的"纯逻辑"边界。

**判据（什么进这里）**：① 不 import 任何 `net.minecraft.*` / `net.neoforged.*` / `net.minecraftforge.*`；② 只做数学/时间轴/几何这类**可判定**的事；③ 能被 3 行单测覆盖到边界。

**依赖方向**：**不依赖任何本项目包**（最内层）。被 `render/`（渲染前算尺寸/推进时间轴）与 `anim/` 依赖。

**类明细（2 类）**

| 类 | 职责 | 关键 API |
|---|---|---|
| `Animator` | 动画时间轴：帧索引推进、循环/非循环、帧率→tick 换算 | `tick()` → `Advance{PLAYING,LOOPED,FINISHED}` · `restart()` · `ticksPerFrameFor(fps)` |
| `FrameGeometry` | 帧的几何与尺寸决策：内容盒并集、alpha 阈值、**显示缩放唯一入口** | **主入口**：`unionBounds(w,h,frames)` · `scaleForViewport(...)`<br>**配套/legacy**：`targetWidthFor(...)`（宽比例，仅算宽度目标）· `targetHeightFor(...)`（高比例版本，当前渲染未用）· `displayScale(...)` / `scaleFor(...)`（绝对像素版，当前渲染未用）· `ALPHA_THRESHOLD`（内容盒透明度阈值=8） |

> **尺寸决策只有一个主入口**：`scaleForViewport(...)`（宽比例 + 高上限**双约束取更小**）。
> 其余 `scaleFor` / `displayScale` / `targetHeightFor` 是演进过程中留下的**单约束版本**，
> 当前渲染路径**不使用**；保留原因：测试与未来"绝对像素模式"可复用。**新代码请只用 `scaleForViewport` + `targetWidthFor`。**

**共同用法**

```java
// ① 时间轴（每 tick 调一次；HUD 帧率≠TPS，调用方按真实时间累积）
Animator a = new Animator(clip.frameCount(), Animator.ticksPerFrameFor(clip.fps()), true);
if (a.tick() == Animator.Advance.FINISHED) a.restart();

// ② 显示缩放（每帧算；两条约束取更小者 —— 见下方陷阱 A）
float s = FrameGeometry.scaleForViewport(screenW, screenH, contentW, contentH,
                                         0.15f, 0.25f, 48f, 320f);
```

**已知陷阱（都踩过，见 docs/lessons-learned.md）**

| # | 陷阱 | 规则 |
|---|---|---|
| A | **尺寸只做一条约束必然失衡**（#D1）：只按宽度算 ⇒ 近正方形 GUI 下角色过高（实测占屏高 58%）；只按高度算 ⇒ 又过小 | **必须宽比例 + 高上限双约束取更小者**；护栏只做绝对边界兜底，**不得在渲染主路径上做比例钳制** |
| B | 缩放基准用错对象 | 必须按**内容盒**（角色本体，实测 72×92）算，**不是画布**（早期数据 220×124 是 ffmpeg 全画布，勿再用） |
| C | 非法入参 | 全部 `scaleFor*` / `target*` 在非法入参时返回 **1.0**（渲染热路径不抛异常，也不放大） |

**修改注意**：① 本包零依赖是硬约束，加 import 前先问"这属于 core 吗"；② 任何阈值/比例改动**必须同步 `FrameGeometryTest`**（含针对 #D1 事故的回归断言）。
