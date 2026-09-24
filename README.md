# DSH Pet (dshpet-mc)

把 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 的桌面宠物体验移植成 **Minecraft 纯客户端 HUD 桌宠 mod**。

- **平台**：Forge 1.20.1 / NeoForge 1.21.1（Stonecutter 双节点）
- **定位**：纯客户端（`displayTest = IGNORE_SERVER_VERSION`），服务端无需安装
- **素材获取**：**装上即用；首次需下载素材（约 64MB，一次性）**；也可**离线自备**——把 `.gif`/`.png` 放进 `config/dshpet/animations/`，或手动放置整包目录
  - 除「按需下载素材」外**无任何网络请求**；不发送遥测、不联外网
- **状态**：`0.1.0`（M1：单宠物 + 待机动画 + HUD 常驻显示）

---

## 许可（Licensing）—— 请先读这一节

本项目**代码与素材是两套许可**，切勿混为一谈：

| 内容 | 许可 | 说明 |
|---|---|---|
| **本项目代码** | **MIT**（见 [`LICENSE`](LICENSE)） | 可自由使用/修改/分发 |
| **动画素材**（运行时按需下载，**本仓库不分发**） | 上游条款：**允许开源使用，禁止商用** | **不适用 MIT**！ |
| 上游代码部分 | MIT（`Copyright (c) 2026 PC2005-cloud`） | 全文见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) |

### 上游署名要求（二创约定，原文摘录）

> **二次创作（二创）约定**：基于本项目的衍生 / 改版 / 换皮作品，在**任何介绍、展示、分发该作品的地方**，须附上原作者 GitHub 地址：<https://github.com/PC2005-cloud/dsh-pet>

⇒ **分发本 mod（jar / Release / 整合包 / 换皮版 / 视频介绍）时，必须带上上述地址。**

### 实务边界（照上游条款执行）

- ✅ **可以**：开源分发、修改、做换皮版（**附署名**）
- ❌ **不可以**：售卖、放进付费整合包、用于付费服务器售卖 —— 素材部分**禁止商用**
- 要商用须**先取得上游素材权利人授权**

上述声明同时随 jar 分发（`META-INF/LICENSE` · `META-INF/LICENSE-UPSTREAM-DSHPET` · `META-INF/THIRD-PARTY-NOTICES.md`），满足 MIT「许可与版权声明须随副本分发」的要求。

---

## 快速开始

1. 把 `dshpet-forge-<版本>+1.20.1.jar` 或 `dshpet-neoforge-<版本>+1.21.1.jar` 放进对应 `mods/`
2. 进世界 → 右下角出现桌宠（默认动画 `dongzhangxiwang`）
3. 自定义动画：把 `.gif` / `.png` 放进 `config/dshpet/animations/`（**同名会覆盖内置**；坏文件自动回退到内置，不会让动作消失）
4. 调试日志：`/dshpet debug on|off|status`（写入 `config/dshpet.toml`）

## 素材来源与版本固定

| 项 | 值 |
|---|---|
| 上游 | [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet) |
| 版本 | `v0.2.11`（pinned commit `d3988fa52fccaae8249d94ba31e9e4bae073362d`） |
| 素材路径 | 上游 `dsh-pet/assets/preview/*.gif`（上游 README 展示用 GIF，已逐字节校验一致） |
| 规格 | 画布 72×92 · 120 帧 / 10s · 12 fps · 1-bit alpha |

## 开发

```bash
# 双节点编译（必带 --no-build-cache）
./gradlew.bat :forge:1.20.1:compileJava :neoforge:1.21.1:compileJava --no-build-cache

# 单测（仅在 forge 节点；neoforge 节点 test 是 NO-SOURCE）
./gradlew.bat :forge:1.20.1:test --rerun-tasks

# 打包
./gradlew.bat :forge:1.20.1:jar :neoforge:1.21.1:jar -x javadoc --no-build-cache
```

- 纪律与守则：[`PROJECT-RULES.md`](PROJECT-RULES.md)
- 差错与教训：[`docs/lessons-learned.md`](docs/lessons-learned.md)
- 设计与审计：[`docs/PLAN.md`](docs/PLAN.md) · [`docs/AUDIT-2026-09-22.md`](docs/AUDIT-2026-09-22.md) · [`docs/AUDIT-standards-2026-09-23.md`](docs/AUDIT-standards-2026-09-23.md)
- 各包说明：`common/src/main/java/com/github/xiaozhaoz1/dshpet/{core,anim,render,config}/README.md`
