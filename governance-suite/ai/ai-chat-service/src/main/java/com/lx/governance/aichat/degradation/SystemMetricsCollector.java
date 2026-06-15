package com.lx.governance.aichat.degradation;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 系统指标采集器
 * 采集 CPU、内存、并发、错误率等指标
 * 集成现有的 AiChatMetrics
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemMetricsCollector {

    private static final String VECTOR_MEMORY_SERVICE_NAME = "vector-memory-service";

    private final RedisTemplate<String, Object> redisTemplate;
    private final DiscoveryClient discoveryClient;

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
                    com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
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
     *
     * 原则说明：
     * 1. 生产环境优先复用注册中心、网关、服务治理平台的现成探活结果，而不是在业务代码里主动发心跳。
     * 2. 对于已接入 Nacos/Actuator 的依赖，降级模块更适合“消费健康状态”而不是“重复实现健康检查”。
     * 3. 当前模块先保留接入位，默认认为可用；后续如果需要，可在这里统一接 Nacos/治理平台暴露的健康状态。
     */
    private Boolean checkAiApiAvailable() {
        return true;
    }

    /**
     * 检查记忆服务可用性
     *
     * 这里优先复用 Nacos / Spring Cloud Discovery 的底层服务发现结果。
     * 对当前单实例 ai-chat-service 来说，只要注册中心里还能发现到可用实例，就认为记忆服务具备可用性。
     * 这代表“注册发现层可用”，不等同于“业务接口 100% 正常”，但比固定返回 true 更合理。
     *
     * 对 getInstances() 的理解补充：
     * 1. 这里不是简单地“每次都先查本地缓存，再查一次 Nacos Server”。
     * 2. 当前 Spring Cloud Alibaba + Nacos 的默认路径下，底层通常会走 Nacos 客户端的订阅模式。
     * 3. 在订阅模式下，Nacos 客户端会优先读取本地维护的 serviceInfo 缓存；本地没有时，才触发 subscribe/query。
     * 4. 后续实例变更通常由客户端本地订阅缓存持续更新，因此这个判断更接近“消费端当前视角下的实例可用性”。
     * 5. Spring Cloud Alibaba 外层还存在 failure tolerance 的兜底缓存，但那不是这里的主判断路径。
     */
    private Boolean checkMemoryServiceAvailable() {
        try {
            List<ServiceInstance> instances = discoveryClient.getInstances(VECTOR_MEMORY_SERVICE_NAME);
            boolean available = instances != null && !instances.isEmpty();
            if (!available) {
                log.warn("未从注册中心发现记忆服务实例: serviceName={}", VECTOR_MEMORY_SERVICE_NAME);
            }
            return available;
        } catch (Exception e) {
            log.error("从注册中心获取记忆服务实例失败: serviceName={}", VECTOR_MEMORY_SERVICE_NAME, e);
            return false;
        }
    }

    /**
     * 检查 Redis 可用性
     *
     * 当前保留一个轻量本地检查，因为 Redis 通常不是通过业务侧 RPC 调用注册发现的。
     * 如果后续基础设施层已经统一提供 Redis 健康状态，也可以改成读取外部健康信号。
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
