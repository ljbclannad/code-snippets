package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.Step;
import com.health.agent.model.TaskStatus;
import com.lx.governance.agentplatform.model.ApprovalStatus;
import com.lx.governance.agentplatform.model.ApprovalTask;
import com.lx.governance.agentplatform.model.Run;
import com.lx.governance.agentplatform.model.RunPersistenceSnapshot;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.RunType;
import com.lx.governance.agentplatform.runtime.InMemoryRunPersistenceRepository;

@ExtendWith(MockitoExtension.class)
class DoctorAgentApprovalResumeExecutorTest {

    private DoctorPlatformResumeStateService platformResumeStateService;

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private PendingTaskService pendingTaskService;

    private DoctorAgentApprovalResumeExecutor approvalResumeExecutor;

    @BeforeEach
    void setUp() {
        platformResumeStateService = new DoctorPlatformResumeStateService(new InMemoryRunPersistenceRepository());
        approvalResumeExecutor = new DoctorAgentApprovalResumeExecutor(
                platformResumeStateService,
                sessionService,
                pendingTaskService);
    }

    @Test
    void shouldLoadApprovedResumeStateFromSessionAndTask() {
        AgentContext context = AgentContext.initialize("C001", "D001", "目标");
        AgentSession session = AgentSession.builder()
                .id("S001")
                .customerId("C001")
                .doctorId("D001")
                .goal("目标")
                .status(SessionStatus.PAUSED.name())
                .historyJson(JSON.toJSONString(List.of(Step.thought("需要审批"))))
                .messagesJson(JSON.toJSONString(List.of(Message.user("hello"))))
                .contextJson(JSON.toJSONString(context))
                .currentStep(3)
                .build();
        PendingTask task = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .status(TaskStatus.APPROVED.name())
                .inputJson(JSON.toJSONString(ActionDecision.builder()
                        .toolName("get_weight_history")
                        .reason("继续执行")
                        .build()))
                .build();

        when(sessionService.getById("S001")).thenReturn(session);
        when(pendingTaskService.findById("101")).thenReturn(task);

        AgentApprovalResumeState state = approvalResumeExecutor.loadApprovedResumeState("S001", "101");

        assertEquals("S001", state.getSession().getId());
        assertEquals(3, state.getCurrentStep());
        assertEquals(1, state.getHistory().size());
        assertEquals("get_weight_history", state.getApprovedAction().getToolName());
    }

    @Test
    void shouldPreferPlatformSnapshotForSessionStateWhenAvailable() {
        InMemoryRunPersistenceRepository repository = new InMemoryRunPersistenceRepository();
        platformResumeStateService = new DoctorPlatformResumeStateService(repository);
        approvalResumeExecutor = new DoctorAgentApprovalResumeExecutor(
                platformResumeStateService,
                sessionService,
                pendingTaskService);

        repository.save(RunPersistenceSnapshot.builder()
                .run(Run.builder()
                        .runId("S001")
                        .sessionId("S001")
                        .workflowId("doctor-patient-task-v1")
                        .agentId("doctor-assistant-agent")
                        .runType(RunType.TASK)
                        .status(RunStatus.WAITING_APPROVAL)
                        .input(Map.of(
                                "customerId", "C001",
                                "doctorId", "D001",
                                "goal", "目标",
                                "historyJson", JSON.toJSONString(List.of(Step.thought("来自平台快照"))),
                                "messagesJson", JSON.toJSONString(List.of(Message.user("hello"))),
                                "contextJson", JSON.toJSONString(AgentContext.initialize("C001", "D001", "目标")),
                                "currentStep", 4))
                        .output(Map.of())
                        .build())
                .approvalTask(ApprovalTask.builder()
                        .approvalTaskId("101")
                        .runId("S001")
                        .taskKey("get_weight_history")
                        .status(ApprovalStatus.APPROVED)
                        .build())
                .build());
        PendingTask task = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .status(TaskStatus.APPROVED.name())
                .inputJson(JSON.toJSONString(ActionDecision.builder()
                        .toolName("get_weight_history")
                        .reason("继续执行")
                        .build()))
                .build();

        when(pendingTaskService.findById("101")).thenReturn(task);

        AgentApprovalResumeState state = approvalResumeExecutor.loadApprovedResumeState("S001", "101");

        assertEquals("S001", state.getSession().getId());
        assertEquals(4, state.getCurrentStep());
        assertEquals("来自平台快照", state.getHistory().get(0).getContent());
    }

    @Test
    void shouldRejectResumeWhenTaskNotApproved() {
        AgentSession session = AgentSession.builder()
                .id("S001")
                .customerId("C001")
                .doctorId("D001")
                .goal("目标")
                .status(SessionStatus.PAUSED.name())
                .historyJson("[]")
                .messagesJson("[]")
                .contextJson(JSON.toJSONString(AgentContext.initialize("C001", "D001", "目标")))
                .currentStep(1)
                .build();
        PendingTask task = PendingTask.builder()
                .id(101L)
                .agentSessionId("S001")
                .status(TaskStatus.PENDING.name())
                .build();

        when(sessionService.getById("S001")).thenReturn(session);
        when(pendingTaskService.findById("101")).thenReturn(task);

        assertThrows(AgentException.class, () -> approvalResumeExecutor.loadApprovedResumeState("S001", "101"));
    }
}
