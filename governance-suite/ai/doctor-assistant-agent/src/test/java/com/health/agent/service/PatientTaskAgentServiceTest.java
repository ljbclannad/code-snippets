package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.health.agent.dto.patient.AgentTaskConstraints;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.StructuredFinalAnswer;
import com.health.agent.config.DoctorAgentPlatformProperties;
import com.lx.governance.agentplatform.model.PlatformTraceContext;
import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformContextDomain;
import com.lx.governance.agentplatform.model.PlatformContextField;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.WorkbenchActionType;
import com.lx.governance.agentplatform.model.WorkbenchViewType;

@ExtendWith(MockitoExtension.class)
class PatientTaskAgentServiceTest {

    @Mock
    private ReActAgentEngine agentEngine;

    private PatientTaskAgentService service;

    @BeforeEach
    void setUp() {
        service = new PatientTaskAgentService(
                new PatientTaskSafetyService(),
                null,
                null,
                null,
                agentEngine,
                new PatientTaskAgentResultMapper(),
                new PatientReplyComposer(),
                null,
                null,
                new DoctorAgentPlatformProperties());
    }

    @Test
    void shouldUseRealAgentResultWhenExecutionSucceeds() {
        when(agentEngine.executeStream(any(), any())).thenReturn(AgentResult.success(
                "agent-session-1",
                StructuredFinalAnswer.builder()
                        .summary("患者近期进入减重停滞期，需要补充近期执行数据。")
                        .riskLevel("MEDIUM")
                        .needsAttention(true)
                        .keyFindings(List.of("近两周体重变化较小"))
                        .recommendedActions(List.of("补充近3天饮食和体重记录"))
                        .supportingEvidence(List.of("WEIGHT_HISTORY", "SESSION_CONTEXT"))
                        .evidenceSources(List.of("AI_CHAT_MEMORY_SNAPSHOT", "AI_CHAT_SESSION_CONTEXT"))
                        .uncertainties(List.of("缺少完整饮食记录"))
                        .inferences(List.of("当前适合继续随访分析"))
                        .build(),
                List.of(),
                2));

        DoctorAssistantTaskResponse response = service.analyze(baseRequest(TaskType.PATIENT_FOLLOW_UP_ANALYSIS));

        assertEquals(DoctorAssistantTaskStatus.SUCCESS, response.getStatus());
        assertEquals("agent-session-1", response.getAgentSessionId());
        assertTrue(response.getPatientSafeReply().contains("补充近3天饮食和体重记录"));
        assertEquals("MEDIUM", response.getStructuredResult().getRiskLevel());
        assertEquals("AUTO_COMPLETED", response.getDoctorWorkbenchPayload().getReviewStatus());
        assertNotNull(response.getPlatformWorkbenchPayload());
        assertEquals(WorkbenchViewType.SUMMARY, response.getPlatformWorkbenchPayload().getViewType());
        assertEquals(1, response.getPlatformWorkbenchPayload().getAvailableActions().size());
        assertEquals(WorkbenchActionType.RETRY,
                response.getPlatformWorkbenchPayload().getAvailableActions().get(0).getActionType());
        assertEquals("trace-1", response.getTraceContext().getTraceId());
        assertEquals("agent-session-1", response.getTraceContext().getRunId());
    }

    @Test
    void shouldPassPlatformContextIntoExecutionCommand() {
        when(agentEngine.executeStream(any(), any())).thenReturn(AgentResult.success(
                "agent-session-ctx",
                StructuredFinalAnswer.builder()
                        .summary("上下文接入成功")
                        .riskLevel("LOW")
                        .needsAttention(false)
                        .recommendedActions(List.of("继续观察"))
                        .supportingEvidence(List.of("PATIENT_MESSAGE", "AI_CHAT_SESSION_CONTEXT"))
                        .evidenceSources(List.of("PATIENT_MESSAGE"))
                        .build(),
                List.of(),
                1));

        service.analyze(baseRequest(TaskType.PATIENT_FOLLOW_UP_ANALYSIS));

        ArgumentCaptor<com.health.agent.dto.agent.AgentExecutionCommand> captor =
                ArgumentCaptor.forClass(com.health.agent.dto.agent.AgentExecutionCommand.class);
        verify(agentEngine).executeStream(captor.capture(), any());
        assertNotNull(captor.getValue().getPlatformContext());
        assertTrue(captor.getValue().getPlatformContext().getFields().stream()
                .anyMatch(field -> field.getDomain() == PlatformContextDomain.CONVERSATION_CONTEXT));
    }

    @Test
    void shouldEscalateWhenSafetySanitizerBlocksAgentOutput() {
        when(agentEngine.executeStream(any(), any())).thenReturn(AgentResult.success(
                "agent-session-2",
                StructuredFinalAnswer.builder()
                        .summary("目前可以明确诊断为药物相关不适。")
                        .riskLevel("LOW")
                        .needsAttention(false)
                        .keyFindings(List.of("症状与近期用药相关"))
                        .recommendedActions(List.of("继续观察"))
                        .supportingEvidence(List.of("PATIENT_MESSAGE", "SESSION_CONTEXT"))
                        .evidenceSources(List.of("PATIENT_MESSAGE", "AI_CHAT_SESSION_CONTEXT"))
                        .uncertainties(List.of("仍需人工复核"))
                        .inferences(List.of("存在诊断性表述"))
                        .build(),
                List.of(),
                1));

        DoctorAssistantTaskResponse response = service.analyze(baseRequest(TaskType.PATIENT_FOLLOW_UP_ANALYSIS));

        assertEquals(DoctorAssistantTaskStatus.PENDING_APPROVAL, response.getStatus());
        assertTrue(response.getNeedsHumanReview());
        assertTrue(response.getPatientSafeReply().contains("不适合在线直接给出处方、药物调整或诊断结论"));
        assertEquals("HIGH", response.getStructuredResult().getRiskLevel());
    }

    @Test
    void shouldReturnPendingApprovalWhenAgentRequiresHumanReview() {
        PendingTask pendingTask = PendingTask.builder()
                .id(101L)
                .agentSessionId("agent-session-3")
                .toolName("create_follow_up_task")
                .toolDescription("创建随访任务")
                .reason("需要医生确认")
                .status("PENDING")
                .build();
        when(agentEngine.executeStream(any(), any())).thenReturn(AgentResult.pendingApproval(
                "agent-session-3",
                pendingTask,
                List.of(),
                2));

        DoctorAssistantTaskResponse response = service.analyze(baseRequest(TaskType.NEXT_FOLLOW_UP_ACTION_RECOMMENDATION));

        assertEquals(DoctorAssistantTaskStatus.PENDING_APPROVAL, response.getStatus());
        assertTrue(response.getNeedsHumanReview());
        assertNotNull(response.getPendingTask());
        assertEquals(101L, response.getPendingTask().getPendingTaskId());
        assertEquals("PENDING_REVIEW", response.getDoctorWorkbenchPayload().getReviewStatus());
        assertEquals(WorkbenchViewType.APPROVAL, response.getPlatformWorkbenchPayload().getViewType());
        assertEquals(4, response.getPlatformWorkbenchPayload().getAvailableActions().size());
        assertEquals("agent-session-3", response.getTraceContext().getRunId());
    }

    @Test
    void shouldFallbackToTemplateAnalysisWhenAgentReturnsError() {
        when(agentEngine.executeStream(any(), any())).thenReturn(
                AgentResult.error("agent-session-4", "engine failure", List.of(), 1));

        DoctorAssistantTaskResponse response = service.analyze(baseRequest(TaskType.PATIENT_FOLLOW_UP_ANALYSIS));

        assertEquals(DoctorAssistantTaskStatus.SUCCESS, response.getStatus());
        assertTrue(response.getPatientSafeReply().contains("整理了这次随访"));
        assertEquals(2, response.getStructuredResult().getSupportingEvidence().size());
        assertEquals("AUTO_COMPLETED", response.getDoctorWorkbenchPayload().getReviewStatus());
    }

    @Test
    void shouldReturnFailureWhenTemplateFallbackIsDisabled() {
        DoctorAgentPlatformProperties properties = new DoctorAgentPlatformProperties();
        properties.setFallbackToTemplateOnAgentError(false);
        service = new PatientTaskAgentService(
                new PatientTaskSafetyService(),
                null,
                null,
                null,
                agentEngine,
                new PatientTaskAgentResultMapper(),
                new PatientReplyComposer(),
                null,
                null,
                properties);
        when(agentEngine.executeStream(any(), any())).thenReturn(
                AgentResult.error("agent-session-5", "engine failure", List.of(), 1));

        DoctorAssistantTaskResponse response = service.analyze(baseRequest(TaskType.PATIENT_FOLLOW_UP_ANALYSIS));

        assertEquals(DoctorAssistantTaskStatus.ERROR, response.getStatus());
        assertEquals("AGENT_STATUS_ERROR", response.getFallbackReason());
        assertTrue(response.getErrorMessage().contains("关闭模板兜底"));
    }

    private DoctorAssistantTaskRequest baseRequest(TaskType taskType) {
        return DoctorAssistantTaskRequest.builder()
                .taskType(taskType)
                .goal("命中任务")
                .customerId("C001")
                .doctorId("D001")
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .patientMessage("请帮我分析最近一周的情况")
                .conversationId("conv-1")
                .correlationId("corr-1")
                .traceContext(PlatformTraceContext.builder()
                        .traceId("trace-1")
                        .conversationId("conv-1")
                        .correlationId("corr-1")
                        .build())
                .platformContext(PlatformContext.builder()
                        .contextId("ctx:conv-1")
                        .conversationId("conv-1")
                        .customerId("C001")
                        .doctorId("D001")
                        .field(PlatformContextField.builder()
                                .fieldKey("session_context")
                                .domain(PlatformContextDomain.CONVERSATION_CONTEXT)
                                .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                                .source("AI_CHAT_SESSION_CONTEXT")
                                .value(Map.of("conversationPhase", "follow_up", "currentTopic", "血糖复盘"))
                                .build())
                        .field(PlatformContextField.builder()
                                .fieldKey("patient_message")
                                .domain(PlatformContextDomain.CONVERSATION_CONTEXT)
                                .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                                .source("PATIENT_MESSAGE")
                                .value("请帮我分析最近一周的情况")
                                .build())
                        .build())
                .sessionContext(Map.of(
                        "conversationPhase", "follow_up",
                        "currentTopic", "血糖复盘"))
                .profileSnapshot(Map.of(
                        "age", 52,
                        "gender", "female"))
                .memorySnapshot(MemorySnapshot.builder()
                        .shortTermMemories(List.of("近3天血糖偏高"))
                        .longTermMemories(List.of("既往存在糖代谢异常"))
                        .build())
                .triggerDecision(Map.of("summary", "命中升级"))
                .constraints(AgentTaskConstraints.builder()
                        .patientVisibleResponseOnly(Boolean.TRUE)
                        .requireEvidence(Boolean.TRUE)
                        .build())
                .build();
    }
}
