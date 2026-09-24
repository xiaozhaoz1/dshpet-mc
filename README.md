# DSH Pet for Minecraft（dshpet-mc）

一个 **Minecraft 纯客户端 mod**：**为 dsh（DeepSeek Harness）提供接入 MC 的框架**，让 dsh 能够实时「看见」并驱动 Minecraft；同时自带一只桌面宠物 HUD 作为该框架的首个可视化载体。

> **一句话**：dsh ↔ MC 的桥梁，宠物是它的第一个"身体"。

- **平台**：Forge 1.20.1 / NeoForge 1.21.1（Stonecutter 双节点，同一套源码）
- **定位**：**纯客户端**（`displayTest = IGNORE_SERVER_VERSION`）—— 服务端无需安装、不碰服务器、玩家无需开端口
- **状态**：`0.1.0`（宠物 HUD 已可用；dsh 接入框架规划中，见下）

---

## 这个项目做什么

### ① dsh 接入框架（核心目标）

让 dsh 侧（本机或云端）与 MC 建立**持续连接**，从而：

| 能力 | 说明 | 状态 |
|---|---|---|
| **看** | 游戏事件流（受伤/入夜/生物群系/宠物被点击…）+ 周期状态快照 | 规划中 |
| **看（画面）** | 按需截图（免去人工截图/口述参数） | 规划中 |
| **说** | 让宠物主动说话（气泡 + 聊天栏），用于陪玩/提醒 | 规划中 |
| **动** | 切动画、显隐、移动、缩放（调试 + 事件反应） | 规划中 |
| **调** | 远程改配置（缩放模式/整数倍/素材包） | 规划中 |

**架构取向**（调研 [Easy LLM](https://modrinth.com/mod/easy-llm)、[BeaCraft](https://modrinth.com/mod/projectbea)、[elly-ai-agent](https://github.com/Smekkamite/elly-ai-agent)、[Voyager](https://github.com/MineDojo/Voyager) 后的结论）：

```
dsh 侧（大脑 + 服务端）                MC 侧（感官 + 手脚 + 客户端）
  监听 127.0.0.1 / 可配云端地址          主动连出去（endpoint 可配）
  GET  /stream   (SSE 事件流)  ──────►   订阅事件
  POST /event /state           ◄──────   上报事件与状态
  POST /say /anim /act         ──────►   说话 / 切动画 / 动作
```
- **MC 主动连出** ⇒ 本机与云端**同一套代码**，玩家家里**不需要端口转发**
- **策略/记忆/LLM 全在 dsh 侧**，mod 侧只做感官与手脚（保持薄、可测、无 LLM 依赖）
- 默认**只读**、默认**关闭**；云端场景需 token 认证（详见后续文档）

### ② 桌面宠物 HUD（已可用）

框架的首个载体，也是开发期的可视化调试手段：

- 屏幕右下角常驻，**暂停菜单之上也可见**
- **三档缩放模式**：`native`（1 texel = 1 物理像素，最锐利）/ `pixel_perfect`（整数倍，默认 **2×**）/ `ratio`（按屏比例，尺寸自由）
- **像素画清晰度方案**：CPU 预重采样（可分离两趟 + 预乘 alpha）+ 1:1 绘制，规避非整数倍缩放的发糊/抖动
- **素材包按需下载**：多镜像并行 + 断点续传 + md5 校验 + 校验后落盘；单包一模型
- **官方配置屏**（模组列表 → 配置）+ 命令族 `/dshpet ...`

---

## 素材与许可（重要）

**代码与素材是两套许可，切勿混为一谈。**

| 内容 | 许可 |
|---|---|
| **本项目代码** | **MIT**（见 [`LICENSE`](LICENSE)） |
| **调试动画素材** | 来自开源项目 **[PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)**，遵循其条款：**允许开源使用、禁止商用** —— **不适用 MIT** |
| 上游代码部分 | MIT（`Copyright (c) 2026 PC2005-cloud`），全文见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) |

**关于素材的两点说明**：

1. **本仓库不分发任何动画素材**（jar 内受限 GIF 数为 **0**）。仓库内只含**元数据清单**（文件名/大小/md5/上游直链），素材在**运行时按需下载**。
2. **默认素材来自 dsh-pet，它只是"调试资源"**——本框架设计上**支持任意第三方素材包**（自定义 manifest 链接 / 手动放置目录），你完全可以换成自己的宠物形象。

**素材获取**：**装上即用；首次需下载素材（约 64MB，一次性）**；也可**离线自备**——把 `.gif`/`.png` 放进 `config/dshpet/animations/`，或手动放置整包目录。

> 除「按需下载素材」外**无任何网络请求**；不发送遥测、不联外网。

**使用上游素材时请遵守**：
- ✅ 可以：开源项目中使用、二创、学习
- ❌ 不可以：售卖、放进付费整合包、用于付费服务器售卖
- 要商用须**先取得上游素材权利人授权**
- 二创/衍生作品请**附上原作者地址**（[PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)），并按上游要求保留署名

---

## 快速开始

```bash
# 双节点编译
./gradlew :forge:1.20.1:compileJava :neoforge:1.21.1:compileJava --no-build-cache

# 全量单测（在 forge 节点上运行）
./gradlew :forge:1.20.1:test --rerun-tasks

# 打包
./gradlew :forge:1.20.1:jar :neoforge:1.21.1:jar -x javadoc
```

游戏内：

```
/dshpet assets default     # 下载默认素材包后宠物即出现
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
| **M3–M5** | 交互（拖拽/点击）+ 素材目录/调参 + 用户扩展（`overrides.json`）+ 多来源素材包 |
| **M6** | 完整配置屏（含桥接状态页） |
| **S1a/S2a** | **dsh 接入框架**：MC 侧客户端（SSE + 事件上报 + 断线重放） ↔ dsh 插件骨架 |
| **S3** | 接入 dsh 的 LLM：宠物对话 / 主动发言（节流 + 可静音） |
| **S5** | 云端场景实测（token + 速率限制 + 反代/HTTPS 文档） |

> 桥接协议的完整设计（事件集、回执机制、重连语义、安全边界）在开发期文档中维护；对外的 `docs/PROTOCOL.md` 将在 S1a 完成后按需发布。

---

## 致谢

- 桌面宠物与调试动画素材来自 **[PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)**（MIT 代码 / 素材允许开源使用、禁止商用）
- 桥接架构调研参考 [Easy LLM](https://modrinth.com/mod/easy-llm)、[BeaCraft](https://modrinth.com/mod/projectbea)、[elly-ai-agent](https://github.com/Smekkamite/elly-ai-agent)、[ai-companion-core](https://github.com/chappadodle/ai-companion-core)、[Voyager](https://github.com/MineDojo/Voyager)、[MineAgent](https://github.com/bingdongni/MineAgent)
