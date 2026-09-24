# 第三方声明与署名（THIRD-PARTY NOTICES）

本文件覆盖本项目**内嵌/派生的第三方内容**。分发本 mod（jar/Release/整合包/换皮版）时**必须一并保留**本文件与下列署名。

---

## 1. 动画素材 —— 来自 PC2005-cloud/dsh-pet

| 项 | 内容 |
|---|---|
| 来源 | <https://github.com/PC2005-cloud/dsh-pet>（**必须署名的原作者地址**） |
| 上游版本 | `v0.2.11`（pinned commit `d3988fa52fccaae8249d94ba31e9e4bae073362d`，实测与 `main` 逐字节一致） |
| 素材路径 | 上游 `dsh-pet/assets/preview/*.gif`（README 展示用 GIF） |
| 本项目内嵌位置 | `common/src/main/resources/assets/dshpet/animations/` |

### 上游许可口径（原文摘录，逐条对应）

> ## 许可
>
> - 代码：MIT
> - **素材（动画/提示词/源视频）：允许开源使用，禁止商用**
> - **二次创作（二创）约定**：基于本项目的衍生 / 改版 / 换皮作品，在**任何介绍、展示、分发该作品的地方**，须附上原作者 GitHub 地址：<https://github.com/PC2005-cloud/dsh-pet>

### 本项目遵办方式

1. **署名**：本文件 + `README.md`「许可」节 + jar 内 `META-INF/THIRD-PARTY-NOTICES.md` 三处均给出原作者地址；任何对外发布页（Release/Modrinth/CurseForge/MC百科/视频简介）都应带上该地址。
2. **禁止商用**：本项目**不得用于商业用途**（不得售卖、不得用于付费整合包/付费服务器售卖中）。若要商用，须先取得上游素材权利人授权。
3. **代码与素材分离**：本项目的**代码**为 MIT（见 `LICENSE`）；**素材**不适用 MIT，仅适用上述"允许开源使用、禁止商用"条款。

### 上游完整 MIT 文本（其代码部分，按 MIT 要求一并保留）

```
MIT License

Copyright (c) 2026 PC2005-cloud

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. 诊断用标记图（本项目自制）

| 名称 | 说明 |
|---|---|
| `assets/dshpet/animations/diag_marker.png` | 本项目自绘的坐标标尺图（256×160），**非**上游素材，属本项目 MIT 覆盖范围 |

---

## 3. 依赖

本 mod **不打包任何第三方库**（无 jar-in-jar）。运行时仅依赖 Minecraft 与对应加载器（Forge / NeoForge），各自适用其自身许可。
