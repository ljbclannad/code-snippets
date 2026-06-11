package com.lx.governance.aichat.quota;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户配额
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserQuota {

    /**
     * 用户ID
     */
    private String customerId;

    /**
     * 配额类型
     */
    private QuotaType quotaType;

    /**
     * 总配额限制（token 数量）
     */
    private Integer quotaLimit;

    /**
     * 已使用配额
     */
    private Integer quotaUsed;

    /**
     * 配额重置时间（对于 DAILY/MONTHLY）
     */
    private LocalDateTime resetTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 获取剩余配额
     */
    public int getRemaining() {
        return Math.max(0, quotaLimit - quotaUsed);
    }

    /**
     * 是否超额
     */
    public boolean isExceeded() {
        return quotaUsed >= quotaLimit;
    }

    /**
     * 是否需要重置
     */
    public boolean needsReset() {
        if (quotaType == QuotaType.TOTAL) {
            return false;
        }
        return resetTime != null && LocalDateTime.now().isAfter(resetTime);
    }

    /**
     * 计算下次重置时间
     */
    public LocalDateTime calculateNextResetTime() {
        LocalDateTime now = LocalDateTime.now();
        switch (quotaType) {
            case DAILY:
                return now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            case MONTHLY:
                return now.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            case TOTAL:
            default:
                return null;
        }
    }
}
