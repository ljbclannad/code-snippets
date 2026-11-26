package com.health.agent.controller;

import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.exception.AgentException;
import com.health.agent.model.AgentSession;
import com.health.agent.service.agent.AgentSessionService;
import com.health.agent.service.agent.DoctorRunResumeService;
import com.lx.governance.agentplatform.model.ResumeRunRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerResourceGuardTest {

    private AgentAccessGuard accessGuard;
    private AgentSessionService sessionService;
    private DoctorRunResumeService doctorRunResumeService;
    private ExecutorService streamExecutor;
    private AgentController controller;

    @BeforeEach
    void setUp() {
        accessGuard = mock(AgentAccessGuard.class);
        sessionService = mock(AgentSessionService.class);
        doctorRunResumeService = mock(DoctorRunResumeService.class);
        streamExecutor = new DirectExecutorService();
        controller = new AgentController();
        ReflectionTestUtils.setField(controller, "agentEngine", mock(ReActAgentEngine.class));
        ReflectionTestUtils.setField(controller, "accessGuard", accessGuard);
        ReflectionTestUtils.setField(controller, "sessionService", sessionService);
        ReflectionTestUtils.setField(controller, "doctorRunResumeService", doctorRunResumeService);
        ReflectionTestUtils.setField(controller, "streamExecutor", streamExecutor);
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

    @Test
    void shouldAcquireResumePermitWithDoctorIdFromSession() {
        AgentAccessGuard.AccessPermit permit = mock(AgentAccessGuard.AccessPermit.class);
        AgentSession session = AgentSession.builder().id("1").doctorId("D001").build();
        ResumeRunRequest resumeRunRequest = ResumeRunRequest.builder()
                .runId("1")
                .approvalTaskId("101")
                .build();
        when(sessionService.getById("1")).thenReturn(session);
        when(doctorRunResumeService.prepareResume("1", 101L)).thenReturn(resumeRunRequest);
        when(accessGuard.acquire(eq("D001"), anyBoolean())).thenReturn(permit);

        AgentController.AgentResumeRequest request = new AgentController.AgentResumeRequest();
        request.setSessionId("1");
        request.setApprovedTaskId(101L);

        SseEmitter emitter = controller.resume(request);

        assertEquals(1234L, emitter.getTimeout());
        verify(sessionService).getById("1");
        verify(accessGuard).acquire("D001", true);
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
