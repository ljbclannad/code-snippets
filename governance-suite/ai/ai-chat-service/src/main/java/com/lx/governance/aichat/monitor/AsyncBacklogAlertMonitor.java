package com.lx.governance.aichat.monitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.client.VectorMemoryClientFixed;
import com.lx.governance.aichat.config.AsyncBacklogAlertProperties;
import com.lx.governance.aichat.metrics.AiAsyncBacklogMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncBacklogAlertMonitor {

    private final AsyncBacklogAlertProperties properties;
    private final AiAsyncBacklogMetrics backlogMetrics;
    private final UserProfileClientFixed userProfileClient;
    private final VectorMemoryClientFixed vectorMemoryClient;

    private final Map<String, AlertState> alertStates = new HashMap<>();

    @Scheduled(fixedDelayString = "${ai-chat.async-backlog-alert.check-interval-ms:30000}")
    public void checkBacklogAlerts() {
        if (!properties.isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        for (AlertEvent event : inspectAlerts(now)) {
            if (event.recovered()) {
                log.info(event.message());
            } else {
                log.warn(event.message());
            }
        }
    }

    List<AlertEvent> inspectAlerts(long now) {
        List<AlertEvent> events = new ArrayList<>();
        events.addAll(evaluateMetric(
                "memory_extraction_queue",
                "memory.extraction.queue backlog",
                backlogMetrics.memoryExtractionReadyCount(),
                properties.getQueueThresholds().getMemoryExtractionReadyThreshold(),
                now));
        events.addAll(evaluateMetric(
                "chat_history_sync_queue",
                "chat.history.sync.queue backlog",
                backlogMetrics.chatHistorySyncReadyCount(),
                properties.getQueueThresholds().getChatHistorySyncReadyThreshold(),
                now));
        events.addAll(evaluateMetric(
                "user_profile_pending_requests",
                "user profile pending RPC requests",
                backlogMetrics.userProfilePendingRequestCount(userProfileClient),
                properties.getRpcThresholds().getUserProfilePendingThreshold(),
                now));
        events.addAll(evaluateMetric(
                "vector_memory_pending_requests",
                "vector memory pending RPC requests",
                backlogMetrics.vectorMemoryPendingRequestCount(vectorMemoryClient),
                properties.getRpcThresholds().getVectorMemoryPendingThreshold(),
                now));
        return events;
    }

    private List<AlertEvent> evaluateMetric(
            String key,
            String metricName,
            double currentValue,
            double threshold,
            long now) {
        List<AlertEvent> events = new ArrayList<>();
        AlertState state = alertStates.computeIfAbsent(key, ignored -> new AlertState());
        boolean overThreshold = currentValue >= threshold;

        if (overThreshold) {
            boolean firstTrigger = !state.alerting;
            boolean shouldRepeat = now - state.lastAlertAt >= properties.getRepeatAlertIntervalMs();
            if (firstTrigger || shouldRepeat) {
                state.alerting = true;
                state.lastAlertAt = now;
                events.add(new AlertEvent(false,
                        String.format("异步积压告警 - %s=%s, threshold=%s", metricName, format(currentValue),
                                format(threshold))));
            }
            return events;
        }

        if (state.alerting) {
            state.alerting = false;
            state.lastAlertAt = 0L;
            events.add(new AlertEvent(true,
                    String.format("异步积压恢复 - %s=%s, threshold=%s", metricName, format(currentValue),
                            format(threshold))));
        }
        return events;
    }

    private String format(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }

    private static final class AlertState {
        private boolean alerting;
        private long lastAlertAt;
    }

    record AlertEvent(boolean recovered, String message) {
    }
}
