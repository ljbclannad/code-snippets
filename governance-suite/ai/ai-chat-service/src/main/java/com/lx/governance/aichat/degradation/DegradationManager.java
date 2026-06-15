package com.lx.governance.aichat.degradation;

import com.lx.governance.aichat.metrics.AiChatMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 降级管理器
 * 负责降级状态管理和降级决策
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DegradationManager {

    private final DegradationProperties properties;
    private final AiChatMetrics metrics;

    /**
     * 当前降级上下文
     */
    private final AtomicReference<DegradationContext> currentContext = new AtomicReference<>(
            DegradationContext.builder()
                    .level(DegradationLevel.NORMAL)
                    .reason("系统正常")
                    .triggerTime(LocalDateTime.now())
                    .manualTrigger(false)
                    .build()
    );

    /**
     * 上次检查时间
     */
    private volatile LocalDateTime lastCheckTime = LocalDateTime.now();

    /**
     * 恢复计数器（需要持续满足恢复条件）
     */
    private volatile int recoveryCounter = 0;

    /**
     * 获取当前降级级别
     */
    public DegradationLevel getCurrentLevel() {
        return currentContext.get().getLevel();
    }

    /**
     * 获取当前降级上下文
     */
    public DegradationContext getCurrentContext() {
        return currentContext.get();
    }

    /**
     * 是否处于降级状态
     */
    public boolean isDegraded() {
        return getCurrentLevel().isDegraded();
    }

    /**
     * 检查是否应该启用某个功能
     * 根据当前降级级别判断
     */
    public boolean isFeatureEnabled(String feature) {
        if (!properties.isEnabled()) {
            return true; // 降级功能未启用，所有功能可用
        }

        DegradationLevel level = getCurrentLevel();

        switch (feature) {
            case "recommendation":
                // 相似对话推荐：轻度降级以上关闭
                return level == DegradationLevel.NORMAL;

            case "memory":
                // 记忆检索：中度降级以上关闭
                return level == DegradationLevel.NORMAL || level == DegradationLevel.LIGHT;

            case "fullContext":
                // 完整上下文：重度降级以上关闭
                return level == DegradationLevel.NORMAL
                        || level == DegradationLevel.LIGHT
                        || level == DegradationLevel.MEDIUM;

            case "basicContext":
                // 基础上下文：极限降级以上关闭
                return level != DegradationLevel.CRITICAL && level != DegradationLevel.CIRCUIT_BREAK;

            case "aiApi":
                // AI API 调用：熔断时关闭
                return level != DegradationLevel.CIRCUIT_BREAK;

            default:
                return true;
        }
    }

    /**
     * 根据系统指标自动决策降级级别
     */
    public void autoDecideDegradationLevel(DegradationContext.SystemMetrics metrics) {
        if (!properties.isEnabled() || !properties.isAutoEnabled()) {
            return;
        }

        DegradationLevel currentLevel = getCurrentLevel();
        DegradationLevel targetLevel = calculateTargetLevel(metrics);

        if (targetLevel != currentLevel) {
            if (targetLevel.isMoreSevereThan(currentLevel)) {
                // 需要降级
                degradeTo(targetLevel, buildReason(targetLevel, metrics), false, null, metrics);
            } else {
                // 可能恢复
                checkRecovery(targetLevel, metrics);
            }
        }

        lastCheckTime = LocalDateTime.now();
    }

    /**
     * 手动触发降级
     */
    public void manualDegradeTo(DegradationLevel level, String reason, String operator) {
        degradeTo(level, reason, true, operator);
    }

    /**
     * 手动恢复到正常
     */
    public void manualRecover(String operator) {
        degradeTo(DegradationLevel.NORMAL, "手动恢复", true, operator);
    }

    /**
     * 执行降级
     */
    private void degradeTo(DegradationLevel level, String reason, boolean manual, String operator) {
        degradeTo(level, reason, manual, operator, null);
    }

    private void degradeTo(DegradationLevel level, String reason, boolean manual, String operator,
            DegradationContext.SystemMetrics systemMetrics) {
        DegradationLevel oldLevel = getCurrentLevel();

        DegradationContext newContext = DegradationContext.builder()
                .level(level)
                .reason(reason)
                .triggerTime(LocalDateTime.now())
                .metrics(systemMetrics)
                .manualTrigger(manual)
                .triggeredBy(operator)
                .build();

        currentContext.set(newContext);
        recoveryCounter = 0; // 重置恢复计数器

        if (oldLevel != level) {
            log.warn("服务降级级别变更: {} -> {}, 原因: {}, 手动触发: {}, 操作者: {}",
                    oldLevel.getName(), level.getName(), reason, manual, operator);

            // 记录降级触发
            metrics.recordDegradationTrigger();

            // 更新降级级别指标
            metrics.updateDegradationLevel(level.getLevel());
        }
    }

    /**
     * 检查是否可以恢复
     */
    private void checkRecovery(DegradationLevel targetLevel, DegradationContext.SystemMetrics metrics) {
        // 需要持续满足恢复条件
        recoveryCounter++;

        int requiredCount = properties.getRecovery().getDurationSeconds() / properties.getCheckIntervalSeconds();

        if (recoveryCounter >= requiredCount) {
            degradeTo(targetLevel, "系统负载恢复: " + describeMetrics(metrics), false, null, metrics);
            recoveryCounter = 0;
        } else {
            log.debug("恢复检查中: {}/{}, 目标级别: {}", recoveryCounter, requiredCount, targetLevel.getName());
        }
    }

    /**
     * 根据指标计算目标降级级别
     */
    private DegradationLevel calculateTargetLevel(DegradationContext.SystemMetrics metrics) {
        DegradationLevel dependencyLevel = calculateDependencyLevel(metrics);
        if (dependencyLevel != null) {
            return dependencyLevel;
        }

        DegradationProperties.Thresholds thresholds = properties.getThresholds();
        DegradationLevel currentLevel = getCurrentLevel();
        double factor = currentLevel.isDegraded() ? properties.getRecovery().getFactor() : 1.0;

        // 从高到低检查
        if (shouldDegradeToLevel(metrics, thresholds.getCircuitBreak(), factor)) {
            return DegradationLevel.CIRCUIT_BREAK;
        }
        if (shouldDegradeToLevel(metrics, thresholds.getCritical(), factor)) {
            return DegradationLevel.CRITICAL;
        }
        if (shouldDegradeToLevel(metrics, thresholds.getHeavy(), factor)) {
            return DegradationLevel.HEAVY;
        }
        if (shouldDegradeToLevel(metrics, thresholds.getMedium(), factor)) {
            return DegradationLevel.MEDIUM;
        }
        if (shouldDegradeToLevel(metrics, thresholds.getLight(), factor)) {
            return DegradationLevel.LIGHT;
        }

        return DegradationLevel.NORMAL;
    }

    private DegradationLevel calculateDependencyLevel(DegradationContext.SystemMetrics metrics) {
        if (Boolean.FALSE.equals(metrics.getAiApiAvailable())) {
            return DegradationLevel.CIRCUIT_BREAK;
        }
        if (Boolean.FALSE.equals(metrics.getRedisAvailable())) {
            return DegradationLevel.CRITICAL;
        }
        if (Boolean.FALSE.equals(metrics.getMemoryServiceAvailable())) {
            return DegradationLevel.MEDIUM;
        }
        return null;
    }

    /**
     * 判断是否应该降级到某个级别
     */
    private boolean shouldDegradeToLevel(
            DegradationContext.SystemMetrics metrics,
            DegradationProperties.LevelThreshold threshold,
            double factor) {

        // 任意一个指标超过阈值就触发降级
        if (metrics.getCpuUsage() != null && metrics.getCpuUsage() > threshold.getCpuUsage() * factor) {
            return true;
        }
        if (metrics.getMemoryUsage() != null && metrics.getMemoryUsage() > threshold.getMemoryUsage() * factor) {
            return true;
        }
        if (metrics.getConcurrentRequests() != null && metrics.getConcurrentRequests() > threshold.getConcurrentRequests() * factor) {
            return true;
        }
        if (metrics.getErrorRate() != null && metrics.getErrorRate() > threshold.getErrorRate() * factor) {
            return true;
        }
        if (metrics.getAvgResponseTime() != null && metrics.getAvgResponseTime() > threshold.getAvgResponseTime() * factor) {
            return true;
        }

        return false;
    }

    /**
     * 描述系统指标
     */
    private String describeMetrics(DegradationContext.SystemMetrics metrics) {
        return String.format(
                "CPU=%.1f%%, Memory=%.1f%%, Concurrent=%d, ErrorRate=%.1f%%, AvgRT=%dms, AiApi=%s, MemoryService=%s, Redis=%s",
                metrics.getCpuUsage() != null ? metrics.getCpuUsage() : 0.0,
                metrics.getMemoryUsage() != null ? metrics.getMemoryUsage() : 0.0,
                metrics.getConcurrentRequests() != null ? metrics.getConcurrentRequests() : 0,
                metrics.getErrorRate() != null ? metrics.getErrorRate() : 0.0,
                metrics.getAvgResponseTime() != null ? metrics.getAvgResponseTime() : 0L,
                metrics.getAiApiAvailable(),
                metrics.getMemoryServiceAvailable(),
                metrics.getRedisAvailable());
    }

    private String buildReason(DegradationLevel targetLevel, DegradationContext.SystemMetrics metrics) {
        if (targetLevel == DegradationLevel.CIRCUIT_BREAK && Boolean.FALSE.equals(metrics.getAiApiAvailable())) {
            return "AI API 不可用: " + describeMetrics(metrics);
        }
        if (targetLevel == DegradationLevel.CRITICAL && Boolean.FALSE.equals(metrics.getRedisAvailable())) {
            return "Redis 不可用: " + describeMetrics(metrics);
        }
        if (targetLevel == DegradationLevel.MEDIUM && Boolean.FALSE.equals(metrics.getMemoryServiceAvailable())) {
            return "记忆服务不可用: " + describeMetrics(metrics);
        }
        return "系统负载过高: " + describeMetrics(metrics);
    }
}
