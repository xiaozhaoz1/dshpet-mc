# DSH Pet for Minecraft（dshpet-mc）

一个 **Minecraft 纯客户端桌宠框架**：

- **形象可替换** —— 装进去的可以是**大肥鱼**（默认调试素材），也可以是**任意其他动画包**（第三方 manifest / 本地目录，接口已备好）
- **接入方可替换** —— 通过**插件**接入任意 harness（**dsh 是首个接入方**，其他 harness 同样能接）
- **2D / 3D 都能当桌宠** —— 2D 像素动画（默认），也预留了 **3D 后端**：可把 **[TLM（车万女仆）](https://github.com/TartaricAcid/TouhouLittleMaid) 女仆的 3D 模型**渲染成桌宠

> 一句话：**MC 里的桌宠底座** —— 形象、驱动方、渲染后端三者解耦，各换各的。

- **平台**：Forge 1.20.1 / NeoForge 1.21.1（Stonecutter 双节点，同一套源码）
- **定位**：**纯客户端**（`displayTest = IGNORE_SERVER_VERSION`）—— 服务端无需安装、不碰服务器、玩家无需开端口
- **状态**：`0.1.0`（2D 宠物 HUD 已可用；外部接入与 3D 后端规划中，见下）

---

## 这个项目做什么

### ① 外部接入框架（**与 harness 无关**）

让**外部智能体宿主**与 MC 建立**持续连接**，从而实时"看见"并驱动游戏：

| 能力 | 说明 | 状态 |
|---|---|---|
| **看** | 游戏事件流（受伤/入夜/生物群系/宠物被点击…）+ 周期状态快照 | 规划中 |
| **看（画面）** | 按需截图（免去人工截图/口述参数） | 规划中 |
| **说** | 让宠物主动说话（气泡 + 聊天栏），用于陪玩/提醒 | 规划中 |
| **动** | 切动画、显隐、移动、缩放（调试 + 事件反应） | 规划中 |
| **调** | 远程改配置（缩放模式/整数倍/素材包） | 规划中 |

**为什么是"通用"的**：协议就是**普通 HTTP + JSON**（SSE 推事件流 + POST 发动作），
**任何 harness / 任何语言**都能接 —— 接入方只需实现几个端点（`/stream`、`/say`、`/event`…），
不需要写 MC 代码、不需要装服务端。

```
外部 host（大脑 + 服务端）               MC 侧（感官 + 手脚 + 客户端）
  监听 127.0.0.1 / 可配云端地址            主动连出去（endpoint 可配）
  GET  /stream   (SSE 事件流)  ──────►     订阅事件
  POST /event /state           ◄──────     上报事件与状态
  POST /say /anim /act         ──────►     说话 / 切动画 / 动作
```
- **MC 主动连出** ⇒ 本机与云端**同一套代码**，玩家家里**不需要端口转发**
- **策略/记忆/LLM 全在 host 侧**，mod 侧只做感官与手脚（保持薄、可测、无 LLM 依赖）
- 默认**只读**、默认**关闭**；云端场景需 token 认证（详见后续文档）

**首个接入方：dsh（DeepSeek Harness）** —— 以插件形式接入，见[路线图](#路线图)。

### ② 形象可替换（动画包）

- **一包 = 一只宠物模型**；`/dshpet model <id>` 或配置屏切换
- **默认调试宠物**：昵称「**大肥鱼**」——动画素材来自开源项目 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 的 `assets/preview/`（**上游素材无此名，只是昵称**）
- **任意第三方包**：支持自定义 manifest 链接下载、或手动放置目录（`config/dshpet/animations/` 优先级最高）
- **包按需下载**：多镜像并行 + 断点续传 + md5 校验 + 校验后落盘

### ③ 渲染后端可替换（2D / 3D）

- **2D（默认）**：像素动画贴图。**三档缩放模式**：`native`（1 texel = 1 物理像素）/ `pixel_perfect`（整数倍，默认 **2×**）/ `ratio`（按屏比例）
- **清晰度方案**：CPU 预重采样（可分离两趟 + 预乘 alpha）+ 1:1 绘制，规避非整数倍缩放的发糊/抖动
- **3D（预留接口）**：可把 **[TLM 车万女仆](https://github.com/TartaricAcid/TouhouLittleMaid) 的 3D 模型**作为桌宠渲染 —— 复用玩家已有的女仆模型/皮肤，无需另做素材

---

## 素材与许可（重要）

**代码与素材是两套许可，切勿混为一谈。**

| 内容 | 许可 |
|---|---|
| **本项目代码** | **MIT**（见 [`LICENSE`](LICENSE)） |
| **调试宠物的动画素材**（昵称「大肥鱼」） | 来自开源项目 **[PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)** 的 `assets/preview/`，遵循其条款：**允许开源使用、禁止商用** —— **不适用 MIT** |
| 上游代码部分 | MIT（`Copyright (c) 2026 PC2005-cloud`），全文见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) |

**两点说明**：

1. **本仓库不分发任何动画素材**（jar 内受限 GIF 数为 **0**）。仓库内只含**元数据清单**（文件名/大小/md5/上游直链），素材在**运行时按需下载**。
2. **「大肥鱼」只是"调试资源"** —— 框架设计上支持任意第三方素材包，你完全可以换成自己的宠物形象。

**素材获取**：**装上即用；首次需下载素材（约 64MB，一次性）**；也可**离线自备**——把 `.gif`/`.png` 放进 `config/dshpet/animations/`，或手动放置整包目录。

> 除「按需下载素材」与「外部 host 接入」外**无任何网络请求**；不发送遥测。

**使用上游调试素材时请遵守**：
- ✅ 可以：开源项目中使用、二创、学习
- ❌ 不可以：售卖、放进付费整合包、用于付费服务器售卖
- 要商用须**先取得上游素材权利人授权**
- 二创/衍生作品请**附上原作者地址**（[PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)）

---

## 快速开始

```bash
./gradlew :forge:1.20.1:compileJava :neoforge:1.21.1:compileJava --no-build-cache   # 双节点编译
./gradlew :forge:1.20.1:test --rerun-tasks                                          # 全量单测
./gradlew :forge:1.20.1:jar :neoforge:1.21.1:jar -x javadoc                          # 打包
```

游戏内：

```
/dshpet assets default     # 下载默认素材包（大肥鱼）后宠物即出现
/dshpet model <id>         # 切换宠物模型
/dshpet config             # 打开配置屏
/dshpet debug on|off       # 调试日志
```

---

## 路线图

| 阶段 | 内容 |
|---|---|
| **M1** ✅ | 单宠物 + 待机动画 + HUD 常驻 |
| **M2** | 多实例 + 配置持久化 + 边角锚定 |
| **M3–M5** | 交互（拖拽/点击）+ 素材目录/调参 + 用户扩展（`overrides.json`）+ **多来源素材包**（任意第三方 manifest） |
| **M6** | 完整配置屏（含**接入状态页**） |
| **M8** | **3D 渲染后端**：TLM 女仆模型作为桌宠（接口已预留，先出接口 + 空实现） |
| **S1a/S2a** | **外部接入框架**：MC 侧客户端（SSE + 事件上报 + 断线重放） ↔ **首个 host 插件骨架（dsh）** |
| **S3** | 接入 LLM：宠物对话 / 主动发言（节流 + 可静音） |
| **S5** | 云端场景实测（token + 速率限制 + 反代/HTTPS 文档） |

> 协议完整设计（事件集、回执机制、重连语义、安全边界）在开发期文档中维护；对外的 `docs/PROTOCOL.md` 将在 S1a 完成后按需发布。

---

## 致谢

- 默认调试宠物（昵称「大肥鱼」）的动画素材来自 **[PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)**（MIT 代码 / 素材允许开源使用、禁止商用）
- 接入架构调研参考 [Easy LLM](https://modrinth.com/mod/easy-llm)、[BeaCraft](https://modrinth.com/mod/projectbea)、[elly-ai-agent](https://github.com/Smekkamite/elly-ai-agent)、[ai-companion-core](https://github.com/chappadodle/ai-companion-core)、[Voyager](https://github.com/MineDojo/Voyager)、[MineAgent](https://github.com/bingdongni/MineAgent)

## DSH 接入插件（dsh-mc-bridge）

让 DSH 里的 AI 能**看见**并**驱动**本 mod 的桥。独立仓库（随 dsh 演进，与本 mod 发版节奏不同）：

**https://github.com/xiaozhaoz1/dsh-mc-bridge**

```bash
dsh plugin add https://github.com/xiaozhaoz1/dsh-mc-bridge
```

> 默认 `enabled=false` + `readOnly=true`（装上零副作用、默认不连、不给动作）。
