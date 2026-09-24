# render — HUD 渲染与素材加载（含平台无关的绘制决策）

**作用**：把 `anim/` 解出的帧数据变成屏幕上的宠物：**素材加载（含回退）→ 纹理管理 → 每帧绘制**。

**依赖方向**：依赖 `anim` / `core` / `config` / `DshPetLog` + MC 客户端类型（`Minecraft`/`GuiGraphics`/`DynamicTexture`）。**被平台入口（forge/neoforge）调用**。

**类明细（2 类）**

| 类 | 职责 | 关键 API |
|---|---|---|
| `SpriteBackend` | 素材加载（**§2.1 契约**）+ 纹理生命周期 | `load(name, userDir)` · `textureFor(clip, frame)` · `release(name)` / `releaseAll()` |
| `PetHud` | 宠物状态机（惰性加载/时间推进/尺寸/位置）+ 每帧绘制 + 诊断 | `render(graphics, userAnimDir)`（由平台钩子每帧调用） · `reset()` |

**素材加载契约（docs/PLAN.md §2.1，用户裁定）**

```
解析顺序固定: 用户目录(.gif → .png) → jar 内置(.gif → .png)
用户文件解码失败 ⇒ WARN + continue 回退到内置，绝不整条消失
异常捕获用 Throwable（畸形素材可能抛 Error，错题 #335 教训 3）
```

**渲染要点（都有实测依据，见 docs/AUDIT-2026-09-22.md §八）**

| 项 | 做法 | 为什么 |
|---|---|---|
| 纹理生命周期 | **每动画复用 1 个 `DynamicTexture`**，切帧走 `setPixels` | **NativeImage 层已实测**：100,000 次循环（理论 2.5GB）⇒ committed 净增 **0.0MB**。⚠️ **真实 GL 层（连续切帧的 `setPixels`/`upload`）仍待实机复核** —— 本条是设计定稿，不是"已实机验证" |
| 线程 | 解码可在异步线程；**纹理创建/上传/释放必须回主线程** | `AbstractTexture.getId()` = `assertOnRenderThreadOrInit`（两版一致） |
| 绘制 API | 7 参 `blit(RL, x, y, uOffset, vOffset, uWidth, vHeight)` | **两版签名一致**；避免 1.21.1 独有的 5 参重载 |
| 像素格式 | RGBA 字节 → `NativeImage` 时按 **ABGR** 打包 | `setPixelRGBA` 是小端 ABGR 语义 |
| 尺寸 | **宽比例 + 高上限双约束取更小者**（`FrameGeometry.scaleForViewport`） | 单约束必然在某种屏幕比例下失衡（#D1 事故，两次反复） |

**每帧预算（实测）**：单帧 26KB、12fps ⇒ 平均 **<0.4 KB/帧/宠物**；稳态 **≤1 次 blit**，同一帧重复渲染零分配。

**已知陷阱**

| # | 陷阱 | 规则 |
|---|---|---|
| A | **打开界面时宠物"消失/变暗"** | HUD 钩子（`RenderGuiEvent.Post` / `RenderGuiOverlayEvent.Pre`）**不含 Screen** ⇒ 必须在平台侧另挂 `ScreenEvent.Render.Post`（`LOWEST` 优先级）才能常驻在菜单之上 |
| B | 缩放按画布算 ⇒ 角色过大 | 用**内容盒**（角色本体）算，不是画布 |
| C | 诊断日志刷屏 | 诊断**默认关**、`[DEBUG-DSHPET]` 唯一前缀、每秒一条摘要；开关走 `/dshpet debug on\|off` |
| D | 热重载保留旧纹理 | 切动画/重载时记得 `release`（`reset()` 已含 `releaseAll`） |

**修改注意**：① 改尺寸/位置常量前先看 `core/README` 陷阱 A，并同步 `FrameGeometryTest`；② 任何纹理操作**不要**从异步线程做；③ 本包是唯一能 import MC 客户端类型的地方之一（另一个是平台入口）。
