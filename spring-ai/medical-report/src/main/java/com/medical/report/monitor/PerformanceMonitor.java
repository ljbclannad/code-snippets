package com.medical.report.monitor;

import com.medical.report.manager.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceMonitor {

    private final SseConnectionManager sseConnectionManager;

    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public void logPerformanceMetrics() {
        int activeConnections = sseConnectionManager.getActiveConnections();

        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int threadCount = threadBean.getThreadCount();
        int peakThreadCount = threadBean.getPeakThreadCount();

        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        log.info("=== Performance Metrics ===");
        log.info("SSE Connections: {}", activeConnections);
        log.info("Threads: {} (peak: {})", threadCount, peakThreadCount);
        log.info("Memory: Used={} MB, Free={} MB, Max={} MB",
                usedMemory / 1024 / 1024,
                freeMemory / 1024 / 1024,
                maxMemory / 1024 / 1024);
        log.info("Memory Usage: {}%", (usedMemory * 100) / maxMemory);
    }
}
