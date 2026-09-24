package com.github.xiaozhaoz1.dshpet.config.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 远端素材清单（`index.json`）—— 纯逻辑，零 MC 依赖。
 *
 * <p>字段设计与容错照 TLM 实证（{@code InfoGetManager} 的 `info.json` + 主备双 URL）：
 * 清单本身可含多个包条目；缺字段/坏条目**跳过而非整体失败**（一条坏数据不该让整个下载功能瘫痪）。</p>
 *
 * @param manifestVersion 清单格式版本（当前 1）
 * @param minModVersion   要求的最低 mod 版本（低于则整体不可用，界面提示升级 mod）
 * @param packs           包列表（解析失败的单条已被跳过）
 */
public record AssetManifest(int manifestVersion, String minModVersion, List<AssetPack> packs) {

    /** 当前支持的清单格式版本。 */
    public static final int CURRENT_VERSION = 1;

    public AssetManifest {
        packs = packs == null ? List.of() : List.copyOf(packs);
        minModVersion = minModVersion == null ? "" : minModVersion;
    }

    /** 空清单（清单不可用时使用；不是错误状态，界面显示"无可用素材"）。 */
    public static AssetManifest empty() {
        return new AssetManifest(CURRENT_VERSION, "", List.of());
    }

    /** 按 id 查包。 */
    public AssetPack byId(String id) {
        if (id == null) {
            return null;
        }
        for (AssetPack p : packs) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 默认包（清单里 id = {@code default} 的条目）。 */
    public AssetPack defaultPack() {
        return byId(AssetPack.DEFAULT_PACK_ID);
    }

    /**
     * 解析清单 JSON。
     *
     * <p>容错策略（对齐"输入防御"守则 2.3）：</p>
     * <ul>
     *   <li>语法错误 ⇒ 抛 {@link IllegalArgumentException}（调用方兜底为主备切换/空清单）</li>
     *   <li>单条包解析失败（如 id 非法）⇒ **跳过该条 + 不中断**，其余照常</li>
     *   <li>缺 {@code manifestVersion} ⇒ 视为 {@link #CURRENT_VERSION}（向前兼容）</li>
     * </ul>
     */
    public static AssetManifest parse(String json) {
        Map<String, Object> root = AssetJson.parseObject(json);
        int version = (int) AssetJson.lng(root, "manifestVersion");
        if (version <= 0) {
            version = CURRENT_VERSION;
        }
        String minMod = AssetJson.str(root, "minModVersion");
        List<AssetPack> packs = new ArrayList<>();
        for (Map<String, Object> row : AssetJson.objectList(root.get("assets"))) {
            try {
                packs.add(AssetPack.of(row));
            } catch (Throwable ignored) {
                // 单条坏数据跳过（id 非法/必填缺失）—— 不拖垮整份清单
            }
        }
        return new AssetManifest(version, minMod, packs);
    }

    /** 清单格式是否被本版本支持。 */
    public boolean supported() {
        return manifestVersion <= CURRENT_VERSION;
    }

    /** 诊断摘要。 */
    public String summary() {
        return "manifest v" + manifestVersion + ", packs=" + packs.size()
                + (minModVersion.isBlank() ? "" : ", minMod=" + minModVersion);
    }
}
