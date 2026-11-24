package com.lx.governance.aichat.degradation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 降级配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-chat.degradation")
public class DegradationProperties {

    /**
     * 是否启用降级
     */
    private boolean enabled;

    /**
     * 是否启用自动降级（根据系统负载）
     */
    private boolean autoEnabled = true;

    /**
     * 降级阈值配置
     */
    private Thresholds thresholds = new Thresholds();

    /**
     * 恢复阈值配置（需要低于降级阈值一定比例才恢复）
     */
    private RecoveryThresholds recovery = new RecoveryThresholds();

    /**
     * 降级检查间隔（秒）
     */
    private int checkIntervalSeconds = 10;

    /**
     * 降级阈值
     */
    @Data
    public static class Thresholds {

        /**
         * 轻度降级阈值
         */
        private LevelThreshold light = new LevelThreshold(70.0, 70.0, 100, 5.0, 3000L);

        /**
         * 中度降级阈值
         */
        private LevelThreshold medium = new LevelThreshold(80.0, 80.0, 200, 10.0, 5000L);

        /**
         * 重度降级阈值
         */
        private LevelThreshold heavy = new LevelThreshold(85.0, 85.0, 300, 20.0, 8000L);

        /**
         * 极限降级阈值
         */
        private LevelThreshold critical = new LevelThreshold(90.0, 90.0, 500, 30.0, 10000L);

        /**
         * 熔断阈值
         */
        private LevelThreshold circuitBreak = new LevelThreshold(95.0, 95.0, 1000, 50.0, 15000L);
    }

    /**
     * 单个级别的阈值
     */
    @Data
    public static class LevelThreshold {
        private Double cpuUsage;          // CPU 使用率 %
        private Double memoryUsage;       // 内存使用率 %
        private Integer concurrentRequests; // 并发请求数
        private Double errorRate;         // 错误率 %
        private Long avgResponseTime;     // 平均响应时间 ms

        public LevelThreshold() {
        }

        public LevelThreshold(Double cpuUsage, Double memoryUsage,
                             Integer concurrentRequests, Double errorRate,
                             Long avgResponseTime) {
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.concurrentRequests = concurrentRequests;
            this.errorRate = errorRate;
            this.avgResponseTime = avgResponseTime;
        }
    }

    /**
     * 恢复阈值（降级后需要指标恢复到更低水平才能升级）
     */
    @Data
    public static class RecoveryThresholds {
        /**
         * 恢复因子（实际阈值 = 降级阈值 * 恢复因子）
         * 例如：降级阈值 80%，恢复因子 0.8，则需要降到 64% 才恢复
         */
        private double factor = 0.8;

        /**
         * 持续时间（秒）- 需要持续低于恢复阈值多久才恢复
         */
        private int durationSeconds = 30;
    }
}
