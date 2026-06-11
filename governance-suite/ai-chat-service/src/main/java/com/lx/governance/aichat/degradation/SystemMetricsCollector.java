package com.lx.governance.aichat.degradation;

import com.lx.governance.aichat.metrics.AiChatMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统指标采集器
 * 采集 CPU、内存、并发、错误率等指标
 * 集成现有的 AiChatMetrics
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemMetricsCollector {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    // 并发请求计数
    private final AtomicInteger concurrentRequests = new AtomicInteger(0);

    // 错误计数（滑动窗口）
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong errorRequests = new AtomicLong(0);

    // 响应时间统计
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong responseTimeCount = new AtomicLong(0);

    // JMX Bean
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

    /**
     * 采集当前系统指标
     */
    public DegradationContext.SystemMetrics collect() {
        return DegradationContext.SystemMetrics.builder()
                .cpuUsage(getCpuUsage())
                .memoryUsage(getMemoryUsage())
                .concurrentRequests(concurrentRequests.get())
                .errorRate(getErrorRate())
                .avgResponseTime(getAvgResponseTime())
                .aiApiAvailable(checkAiApiAvailable())
                .memoryServiceAvailable(checkMemoryServiceAvailable())
                .redisAvailable(checkRedisAvailable())
                .build();
    }

    /**
     * 增加并发请求计数
     */
    public void incrementConcurrent() {
        concurrentRequests.incrementAndGet();
    }

    /**
     * 减少并发请求计数
     */
    public void decrementConcurrent() {
        concurrentRequests.decrementAndGet();
    }

    /**
     * 记录请求（成功）
     */
    public void recordRequest() {
        totalRequests.incrementAndGet();
    }

    /**
     * 记录错误请求
     */
    public void recordError() {
        totalRequests.incrementAndGet();
        errorRequests.incrementAndGet();
    }

    /**
     * 记录响应时间
     */
    public void recordResponseTime(long responseTime) {
        totalResponseTime.addAndGet(responseTime);
        responseTimeCount.incrementAndGet();
    }

    /**
     * 重置统计（用于滑动窗口）
     */
    public void resetStatistics() {
        totalRequests.set(0);
        errorRequests.set(0);
        totalResponseTime.set(0);
        responseTimeCount.set(0);
    }

    /**
     * 获取 CPU 使用率
     */
    private Double getCpuUsage() {
        try {
            double load = osBean.getSystemLoadAverage();
            int processors = osBean.getAvailableProcessors();

            if (load < 0) {
                // 某些系统不支持 load average，使用进程 CPU
                if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                    com.sun.management.OperatingSystemMXBean sunOsBean =
                            (com.sun.management.OperatingSystemMXBean) osBean;
                    return sunOsBean.getProcessCpuLoad() * 100;
                }
                return 0.0;
            }

            // load average 转换为百分比
            return (load / processors) * 100;

        } catch (Exception e) {
            log.error("获取 CPU 使用率失败", e);
            return 0.0;
        }
    }

    /**
     * 获取内存使用率
     */
    private Double getMemoryUsage() {
        try {
            long used = memoryBean.getHeapMemoryUsage().getUsed();
            long max = memoryBean.getHeapMemoryUsage().getMax();

            if (max <= 0) {
                return 0.0;
            }

            return (used * 100.0) / max;

        } catch (Exception e) {
            log.error("获取内存使用率失败", e);
            return 0.0;
        }
    }

    /**
     * 获取错误率
     */
    private Double getErrorRate() {
        long total = totalRequests.get();
        if (total == 0) {
            return 0.0;
        }

        long errors = errorRequests.get();
        return (errors * 100.0) / total;
    }

    /**
     * 获取平均响应时间
     */
    private Long getAvgResponseTime() {
        long count = responseTimeCount.get();
        if (count == 0) {
            return 0L;
        }

        return totalResponseTime.get() / count;
    }

    /**
     * 检查 AI API 可用性
     */
    private Boolean checkAiApiAvailable() {
        // TODO: 实现真实的健康检查
        // 可以通过心跳检查或最近调用成功率判断
        return true;
    }

    /**
     * 检查记忆服务可用性
     */
    private Boolean checkMemoryServiceAvailable() {
        // TODO: 实现真实的健康检查
        return true;
    }

    /**
     * 检查 Redis 可用性
     */
    private Boolean checkRedisAvailable() {
        try {
            redisTemplate.opsForValue().get("health:check");
            return true;
        } catch (Exception e) {
            log.error("Redis 健康检查失败", e);
            return false;
        }
    }
}
