package com.lx.governance.aichat.degradation;

import com.lx.governance.aichat.controller.DegradationController;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DegradationSwitchTest {
    private final DegradationProperties properties = new DegradationProperties();
    private final AiChatMetrics metrics = mock(AiChatMetrics.class);
    private final DegradationManager manager = new DegradationManager(properties, metrics);
    private final DegradationController controller = new DegradationController(manager);

    @Test
    void disabledSwitchBlocksAutomaticAndManualCircuitBreak() {
        properties.setEnabled(false);
        manager.autoDecideDegradationLevel(DegradationContext.SystemMetrics.builder().cpuUsage(120.0).build());
        assertEquals(DegradationLevel.NORMAL, manager.getCurrentLevel());
        assertEquals(HttpStatus.CONFLICT, controller.manualDegrade(request()).getStatusCode());
        assertEquals(DegradationLevel.NORMAL, manager.getCurrentLevel());
        verifyNoInteractions(metrics);
    }

    @Test
    void enabledSwitchAllowsManualCircuitBreak() {
        properties.setEnabled(true);
        assertEquals(HttpStatus.OK, controller.manualDegrade(request()).getStatusCode());
        assertEquals(DegradationLevel.CIRCUIT_BREAK, manager.getCurrentLevel());
    }

    private DegradationController.ManualDegradeRequest request() {
        var request = new DegradationController.ManualDegradeRequest();
        request.setLevel("CIRCUIT_BREAK");
        return request;
    }
}
