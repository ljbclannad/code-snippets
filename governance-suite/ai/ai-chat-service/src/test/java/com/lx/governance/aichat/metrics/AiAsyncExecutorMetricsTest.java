package com.lx.governance.aichat.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AiAsyncExecutorMetricsTest {

    @Test
    void shouldBindExecutorGaugesAndRejectedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiAsyncExecutorMetrics metrics = new AiAsyncExecutorMetrics(registry);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("test-executor-");
        executor.initialize();

        metrics.bind("chatExecutor", executor);
        metrics.recordRejected("chatExecutor");

        assertEquals(2.0, registry.get("ai.executor.max.pool.size")
                .tag("executor", "chatExecutor")
                .gauge()
                .value());
        assertEquals(4.0, registry.get("ai.executor.queue.remaining")
                .tag("executor", "chatExecutor")
                .gauge()
                .value());
        assertEquals(1.0, registry.get("ai.executor.rejected")
                .tag("executor", "chatExecutor")
                .counter()
                .count());

        executor.shutdown();
    }
}
