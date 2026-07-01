package com.lx.governance.aichat.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.client.VectorMemoryClientFixed;
import com.lx.governance.aichat.config.AsyncBacklogAlertProperties;
import com.lx.governance.aichat.metrics.AiAsyncBacklogMetrics;

class AsyncBacklogAlertMonitorTest {

    @Test
    void shouldTriggerAndRecoverBacklogAlertsWithoutLogFlooding() {
        AsyncBacklogAlertProperties properties = new AsyncBacklogAlertProperties();
        properties.setRepeatAlertIntervalMs(300000L);
        properties.getQueueThresholds().setMemoryExtractionReadyThreshold(10);
        properties.getQueueThresholds().setChatHistorySyncReadyThreshold(20);
        properties.getRpcThresholds().setUserProfilePendingThreshold(5);
        properties.getRpcThresholds().setVectorMemoryPendingThreshold(5);

        AiAsyncBacklogMetrics backlogMetrics = mock(AiAsyncBacklogMetrics.class);
        UserProfileClientFixed userProfileClient = mock(UserProfileClientFixed.class);
        VectorMemoryClientFixed vectorMemoryClient = mock(VectorMemoryClientFixed.class);

        when(backlogMetrics.memoryExtractionReadyCount()).thenReturn(12D, 12D, 1D);
        when(backlogMetrics.chatHistorySyncReadyCount()).thenReturn(0D, 0D, 0D);
        when(backlogMetrics.userProfilePendingRequestCount(userProfileClient)).thenReturn(0D, 0D, 0D);
        when(backlogMetrics.vectorMemoryPendingRequestCount(vectorMemoryClient)).thenReturn(0D, 0D, 0D);

        AsyncBacklogAlertMonitor monitor = new AsyncBacklogAlertMonitor(
                properties,
                backlogMetrics,
                userProfileClient,
                vectorMemoryClient);

        List<AsyncBacklogAlertMonitor.AlertEvent> firstEvents = monitor.inspectAlerts(1000L);
        List<AsyncBacklogAlertMonitor.AlertEvent> secondEvents = monitor.inspectAlerts(2000L);
        List<AsyncBacklogAlertMonitor.AlertEvent> thirdEvents = monitor.inspectAlerts(400000L);

        assertEquals(1, firstEvents.size());
        assertEquals(false, firstEvents.get(0).recovered());
        assertEquals(0, secondEvents.size());
        assertEquals(1, thirdEvents.size());
        assertEquals(true, thirdEvents.get(0).recovered());
    }
}
