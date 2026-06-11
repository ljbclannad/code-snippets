package com.lx.governance.aichat.quota;

import lombok.Getter;

/**
 * 配额类型
 */
@Getter
public enum QuotaType {

    /**
     * 按日配额
     */
    DAILY("daily", "每日配额"),

    /**
     * 按月配额
     */
    MONTHLY("monthly", "每月配额"),

    /**
     * 总配额（不重置）
     */
    TOTAL("total", "总配额");

    private final String code;
    private final String description;

    QuotaType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static QuotaType fromCode(String code) {
        for (QuotaType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown quota type: " + code);
    }
}
