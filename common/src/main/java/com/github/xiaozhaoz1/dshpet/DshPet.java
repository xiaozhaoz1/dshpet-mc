package com.github.xiaozhaoz1.dshpet;

/** 公共常量（两平台共用；避免各节点各写一份字符串）。 */
public final class DshPet {

    public static final String MOD_ID = "dshpet";

    /**
     * mod 版本 —— **必须与 `gradle.properties` 的 `mod.version` 一致**。
     *
     * <p>用于清单的 {@code minModVersion} 门控与日志；改版本号时两处同步
     * （守则 §5.4：版本号多处同步）。单测 {@code VersionConsistencyTest} 会守护这一点。</p>
     */
    public static final String VERSION = "0.1.0";

    /** 资源命名空间下的素材目录（jar 内置动画）。 */
    public static final String ASSET_ANIM_DIR = "/assets/dshpet/animations/";

    private DshPet() {
    }
}
