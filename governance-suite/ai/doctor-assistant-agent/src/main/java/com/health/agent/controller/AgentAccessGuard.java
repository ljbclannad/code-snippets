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
    private final Map<String, Deque<Long>> patientTaskRequestWindows = new ConcurrentHashMap<>();
    private Semaphore sseConnectionPermits = new Semaphore(1);
    private Semaphore patientTaskPermits = new Semaphore(1);
    private int configuredMaxSseConnections = 1;
    private int configuredMaxConcurrentPatientTasks = 1;

    @Value("${agent.access.max-requests-per-minute:30}")
    private int maxRequestsPerMinute;

    @Value("${agent.access.max-sse-connections:32}")
    private int maxSseConnections;

    @Value("${agent.access.max-patient-task-requests-per-minute:60}")
    private int maxPatientTaskRequestsPerMinute;

    @Value("${agent.access.max-concurrent-patient-tasks:8}")
    private int maxConcurrentPatientTasks;

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

    public AccessPermit acquirePatientTask(String customerId) {
        enforcePatientTaskRateLimit(customerId);
        Semaphore semaphore = patientTaskSemaphore();
        if (!semaphore.tryAcquire()) {
            throw new AgentException("PATIENT_TASK_OVERLOADED: 当前患者任务分析负载较高，请稍后再试");
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

    private void enforcePatientTaskRateLimit(String customerId) {
        String key = customerId == null || customerId.isBlank() ? "anonymous-patient" : customerId;
        long now = clock.millis();
        long windowStart = now - 60_000L;
        Deque<Long> timestamps = patientTaskRequestWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= maxPatientTaskRequestsPerMinute) {
                throw new AgentException("PATIENT_TASK_RATE_LIMITED: 当前患者任务请求过于频繁，请稍后再试");
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

    private Semaphore patientTaskSemaphore() {
        int desiredPermits = Math.max(1, maxConcurrentPatientTasks);
        synchronized (this) {
            if (configuredMaxConcurrentPatientTasks != desiredPermits) {
                patientTaskPermits = new Semaphore(desiredPermits);
                configuredMaxConcurrentPatientTasks = desiredPermits;
            }
            return patientTaskPermits;
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
