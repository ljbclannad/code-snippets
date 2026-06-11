package com.lx.governance.aichat.degradation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 降级监控调度器
 * 定时采集系统指标并触发降级决策
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DegradationScheduler {

    private final DegradationManager degradationManager;
    private final DegradationProperties properties;
    private final SystemMetricsCollector metricsCollector;

    /**
     * 定时检查降级条件
     * 每 10 秒执行一次（可配置）
     */
    @Scheduled(fixedDelayString = "${ai-chat.degradation.check-interval-seconds:10}000")
    public void checkDegradation() {
        if (!properties.isEnabled() || !properties.isAutoEnabled()) {
            return;
        }

        try {
            // 采集系统指标
            DegradationContext.SystemMetrics metrics = metricsCollector.collect();

            // 记录指标日志（仅在降级时或每分钟记录一次）
            if (degradationManager.isDegraded() || System.currentTimeMillis() % 60000 < 10000) {
                log.info("系统指标 - CPU: {:.1f}%, Memory: {:.1f}%, Concurrent: {}, ErrorRate: {:.1f}%, AvgRT: {}ms",
                        metrics.getCpuUsage(),
                        metrics.getMemoryUsage(),
                        metrics.getConcurrentRequests(),
                        metrics.getErrorRate(),
                        metrics.getAvgResponseTime());
            }

            // 执行降级决策
            degradationManager.autoDecideDegradationLevel(metrics);

        } catch (Exception e) {
            log.error("降级检查失败", e);
        }
    }

    /**
     * 定时重置统计数据（滑动窗口）
     * 每分钟重置一次
     */
    @Scheduled(fixedRate = 60000)
    public void resetStatistics() {
        metricsCollector.resetStatistics();
        log.debug("统计数据已重置");
    }
}
