package com.lx.governance.aichat.degradation;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 降级上下文
 * 包含降级决策所需的所有信息
 */
@Data
@Builder
public class DegradationContext {

    /**
     * 当前降级级别
     */
    private DegradationLevel level;

    /**
     * 降级原因
     */
    private String reason;

    /**
     * 触发时间
     */
    private LocalDateTime triggerTime;

    /**
     * 系统指标
     */
    private SystemMetrics metrics;

    /**
     * 是否手动触发
     */
    private boolean manualTrigger;

    /**
     * 触发者（手动触发时）
     */
    private String triggeredBy;

    /**
     * 系统指标
     */
    @Data
    @Builder
    public static class SystemMetrics {

        /**
         * CPU 使用率 (0-100)
         */
        private Double cpuUsage;

        /**
         * 内存使用率 (0-100)
         */
        private Double memoryUsage;

        /**
         * 当前并发请求数
         */
        private Integer concurrentRequests;

        /**
         * 错误率 (0-100)
         */
        private Double errorRate;

        /**
         * 平均响应时间 (ms)
         */
        private Long avgResponseTime;

        /**
         * AI API 可用性 (true/false)
         */
        private Boolean aiApiAvailable;

        /**
         * 记忆服务可用性
         */
        private Boolean memoryServiceAvailable;

        /**
         * Redis 可用性
         */
        private Boolean redisAvailable;
    }
}
