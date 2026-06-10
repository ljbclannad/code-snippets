package com.health.agent.controller;

import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.exception.AgentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerResourceGuardTest {

    private AgentAccessGuard accessGuard;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        accessGuard = mock(AgentAccessGuard.class);
        controller = new AgentController();
        ReflectionTestUtils.setField(controller, "agentEngine", mock(ReActAgentEngine.class));
        ReflectionTestUtils.setField(controller, "accessGuard", accessGuard);
        ReflectionTestUtils.setField(controller, "sseTimeoutMillis", 1234L);
    }

    @Test
    void shouldAcquireSsePermitWithDoctorIdAndConfiguredTimeout() {
        AgentAccessGuard.AccessPermit permit = mock(AgentAccessGuard.AccessPermit.class);
        when(accessGuard.acquire(eq("D001"), anyBoolean())).thenReturn(permit);

        AgentController.AgentExecuteRequest request = new AgentController.AgentExecuteRequest();
        request.setGoal("分析减重进展");
        request.setCustomerId("C001");
        request.setDoctorId("D001");

        SseEmitter emitter = controller.execute(request);

        assertEquals(1234L, emitter.getTimeout());
        verify(accessGuard).acquire("D001", true);
    }

    @Test
    void shouldRejectBeforeCreatingStreamWhenGuardBlocks() {
        when(accessGuard.acquire(eq("D001"), anyBoolean()))
            .thenThrow(new AgentException("RATE_LIMITED: 当前医生请求过于频繁，请稍后重试"));

        AgentController.AgentExecuteRequest request = new AgentController.AgentExecuteRequest();
        request.setGoal("分析减重进展");
        request.setCustomerId("C001");
        request.setDoctorId("D001");

        AgentException exception = assertThrows(AgentException.class, () -> controller.execute(request));

        assertEquals("RATE_LIMITED: 当前医生请求过于频繁，请稍后重试", exception.getMessage());
    }
}
