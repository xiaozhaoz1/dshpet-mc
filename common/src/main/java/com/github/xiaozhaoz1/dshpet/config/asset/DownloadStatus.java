package com.github.xiaozhaoz1.dshpet.config.asset;

/**
 * 下载状态机 —— 条目对齐 TLM 的 {@code DownloadStatus}（含 {@code NEED_UPDATE} 版本比对语义）。
 */
public enum DownloadStatus {

    /** 清单里有、本地没有。 */
    NOT_DOWNLOADED("未下载"),
    /** 排队等待（同一时刻只下一条，其余排队）。 */
    QUEUED("排队中"),
    /** 下载中（带百分比）。 */
    DOWNLOADING("下载中"),
    /** 已下载且校验通过。 */
    DOWNLOADED("已安装"),
    /** 校验失败/网络失败/解包失败 —— 可重试。 */
    FAILED("失败"),
    /** 本地版本低于清单版本。 */
    NEED_UPDATE("可更新");

    private final String label;

    DownloadStatus(String label) {
        this.label = label;
    }

    /** 中文展示名（一期文案；将来接 lang 键时改由 i18n 提供）。 */
    public String label() {
        return label;
    }

    /** 是否"有活可做"（可下载/可重试/可更新）。 */
    public boolean actionable() {
        return this == NOT_DOWNLOADED || this == FAILED || this == NEED_UPDATE;
    }
}
