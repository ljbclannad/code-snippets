package com.lx.governance.aichat.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class AiAsyncExecutorMetrics {

    private final MeterRegistry meterRegistry;
    private final Set<String> boundExecutors = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Counter> rejectedCounters = new ConcurrentHashMap<>();

    public AiAsyncExecutorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void bind(String executorName, ThreadPoolTaskExecutor executor) {
        if (executorName == null || executorName.isBlank() || executor == null || !boundExecutors.add(executorName)) {
            return;
        }

        ThreadPoolExecutor delegate = executor.getThreadPoolExecutor();
        if (delegate == null) {
            return;
        }

        Gauge.builder("ai.executor.active", delegate, ThreadPoolExecutor::getActiveCount)
                .description("Active thread count by executor")
                .tag("executor", executorName)
                .register(meterRegistry);
        Gauge.builder("ai.executor.pool.size", delegate, ThreadPoolExecutor::getPoolSize)
                .description("Current pool size by executor")
                .tag("executor", executorName)
                .register(meterRegistry);
        Gauge.builder("ai.executor.max.pool.size", delegate, ThreadPoolExecutor::getMaximumPoolSize)
                .description("Maximum pool size by executor")
                .tag("executor", executorName)
                .register(meterRegistry);
        Gauge.builder("ai.executor.queue.size", delegate, threadPoolExecutor -> threadPoolExecutor.getQueue().size())
                .description("Queue size by executor")
                .tag("executor", executorName)
                .register(meterRegistry);
        Gauge.builder("ai.executor.queue.remaining", delegate,
                threadPoolExecutor -> threadPoolExecutor.getQueue().remainingCapacity())
                .description("Remaining queue capacity by executor")
                .tag("executor", executorName)
                .register(meterRegistry);
    }

    public void recordRejected(String executorName) {
        String safeExecutorName = executorName == null || executorName.isBlank() ? "unknown" : executorName;
        rejectedCounters.computeIfAbsent(safeExecutorName,
                key -> Counter.builder("ai.executor.rejected")
                        .description("Rejected executor submissions by executor")
                        .tag("executor", safeExecutorName)
                        .register(meterRegistry))
                .increment();
    }
}
