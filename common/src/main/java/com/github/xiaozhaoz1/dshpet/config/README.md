# config — 配置与路径（唯一入口）

**作用**：所有配置读写与路径拼接的**单一出口**，避免各处各写（本会话早期就吃过"手写 JSON 解析器"的亏，见下方陷阱 A）。

**依赖方向**：依赖顶层 `DshPetLog`（日志门面）；**被 `render/` 与平台入口依赖**。**不 import 任何 MC 类型** —— 平台差异用 stonecutter **条件导入**隔离（见 `DshPetConfig`）。

**类明细（3 类）**

| 类 | 职责 | 关键 API |
|---|---|---|
| `DshPetConfig` | 官方 Spec 定义（**标准写法**）：字段/默认值/注释由框架管 | `SPEC`（注册用） · `DEBUG`（`ConfigValue<Boolean>`，支持运行期 `set`+`save`） |
| `SharedConfig` | **语义化门面**：业务层只调这里，不碰平台 Spec 类型 | `debug()` · `setDebug(boolean)`（写入并落盘） |
| `ConfigPaths` | 路径唯一来源（配置根 / 用户动画目录） | `init(configDir)`（平台入口注入） · `root()` · `animationsDir()` |

**配置载体（2026-09-23 标准化后）**

```
config/dshpet.toml          ← 官方 Spec 生成（forge: ModLoadingContext.registerConfig / neoforge: modContainer.registerConfig）
config/dshpet/animations/   ← 用户动画目录（.gif / .png，优先于 jar 内置）
```

**写法要点（多版本标准姿势）**

```java
// 两版 Spec 类不同名 ⇒ 条件导入 + 条件字段（照 LMA 同款写法）
//? if 1.20.1 {
import net.minecraftforge.common.ForgeConfigSpec;
//?} else {
import net.neoforged.neoforge.common.ModConfigSpec;
//?}
```

**已知陷阱**

| # | 陷阱 | 规则 |
|---|---|---|
| A | **不要自己写 JSON 解析器**（2026-09-23 社区标准审计判定为差距）：手写解析既无范围校验，又要自己处理坏文件 | 字段一律走官方 Spec（`define(...)` 自带默认值/类型/范围） |
| B | 渲染热路径读配置可能抛异常（配置未加载/被外部损坏） | `SharedConfig` 的读路径 **`catch (Throwable)` + 返回默认值**，绝不打断渲染 |
| C | 写配置失败不应影响本会话 | `setDebug` 捕获异常后**仅告警**，内存态仍生效（下次启动回落文件值） |
| D | 文件名/路径别在各处硬编码 | 一律经 `ConfigPaths`（`root()` / `animationsDir()`） |

**修改注意**：① 新增配置项：在 `DshPetConfig` 里 `define(...)` + 在 `SharedConfig` 加语义化读写（**不要**让业务层直接碰 `DEBUG.get()`）；② 改路径约定要同步 `ConfigPaths` 与本 README；③ 平台注册点在两个入口类，**双平台都要挂**（漏一个 ⇒ 该平台配置不生成）。
