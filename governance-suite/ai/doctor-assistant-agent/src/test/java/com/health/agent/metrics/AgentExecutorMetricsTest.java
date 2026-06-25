package com.health.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AgentExecutorMetricsTest {

    @Test
    void shouldBindExecutorMetricsAndRejectedCounter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentMetrics agentMetrics = new AgentMetrics(meterRegistry);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            2,
            60000L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(4)
        );

        agentMetrics.bindExecutor("agent-stream-executor", executor);
        agentMetrics.recordExecutorRejected("agent-stream-executor");

        assertEquals(
            2.0,
            meterRegistry.get("agent.executor.max.pool.size")
                .tag("executor", "agent-stream-executor")
                .gauge()
                .value()
        );
        assertEquals(
            4.0,
            meterRegistry.get("agent.executor.queue.remaining")
                .tag("executor", "agent-stream-executor")
                .gauge()
                .value()
        );
        assertEquals(
            1.0,
            meterRegistry.get("agent.executor.rejected")
                .tag("executor", "agent-stream-executor")
                .counter()
                .count()
        );

        executor.shutdownNow();
    }
}
