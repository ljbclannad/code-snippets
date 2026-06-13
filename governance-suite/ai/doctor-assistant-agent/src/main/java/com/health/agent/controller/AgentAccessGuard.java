package com.health.agent.controller;

import com.health.agent.exception.AgentException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 入口资源保护：请求限流和 SSE 连接上限。
 */
@Component
public class AgentAccessGuard {

    private final Clock clock;
    private final Map<String, Deque<Long>> requestWindows = new ConcurrentHashMap<>();
    private Semaphore sseConnectionPermits = new Semaphore(1);
    private int configuredMaxSseConnections = 1;

    @Value("${agent.access.max-requests-per-minute:30}")
    private int maxRequestsPerMinute;

    @Value("${agent.access.max-sse-connections:32}")
    private int maxSseConnections;

    public AgentAccessGuard() {
        this(Clock.systemUTC());
    }

    AgentAccessGuard(Clock clock) {
        this.clock = clock;
    }

    public AccessPermit acquire(String doctorId, boolean sse) {
        enforceRateLimit(doctorId);
        if (!sse) {
            return AccessPermit.noop();
        }

        Semaphore semaphore = semaphore();
        if (!semaphore.tryAcquire()) {
            throw new AgentException("SSE_CONNECTION_LIMIT: 当前流式连接数已达到上限");
        }
        return new AccessPermit(semaphore);
    }

    private void enforceRateLimit(String doctorId) {
        String key = doctorId == null || doctorId.isBlank() ? "anonymous" : doctorId;
        long now = clock.millis();
        long windowStart = now - 60_000L;
        Deque<Long> timestamps = requestWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= maxRequestsPerMinute) {
                throw new AgentException("RATE_LIMITED: 当前医生请求过于频繁，请稍后重试");
            }
            timestamps.addLast(now);
        }
    }

    private Semaphore semaphore() {
        int desiredPermits = Math.max(1, maxSseConnections);
        synchronized (this) {
            if (configuredMaxSseConnections != desiredPermits) {
                sseConnectionPermits = new Semaphore(desiredPermits);
                configuredMaxSseConnections = desiredPermits;
            }
            return sseConnectionPermits;
        }
    }

    public static class AccessPermit implements AutoCloseable {
        private static final AccessPermit NOOP = new AccessPermit(null);

        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private AccessPermit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        private static AccessPermit noop() {
            return NOOP;
        }

        public void release() {
            if (semaphore != null && released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }

        @Override
        public void close() {
            release();
        }
    }
}
