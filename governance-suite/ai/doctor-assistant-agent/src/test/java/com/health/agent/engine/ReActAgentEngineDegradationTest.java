package com.health.agent.engine;

import com.health.agent.client.DeepSeekClient;
import com.health.agent.exception.AgentException;
import com.health.agent.learning.enums.AgentOutcomeLabel;
import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.service.AgentOutcomeRecorder;
import com.health.agent.learning.service.AgentRunStrategySnapshotRecorder;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.ThoughtResult;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.AgentContextMutationService;
import com.health.agent.service.AgentSessionWorkflowService;
import com.health.agent.service.AgentSessionService;
import com.health.agent.tool.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReActAgentEngineDegradationTest {

    @Mock
    private DeepSeekClient deepSeekClient;

    @Mock
    private ReActPromptBuilder promptBuilder;

    @Mock
    private ReActResponseParser responseParser;

    @Mock
    private ActionExecutor actionExecutor;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private AgentSessionWorkflowService workflowService;

    @Mock
    private AgentAuditService agentAuditService;

    @Mock
    private AgentRunStrategySnapshotRecorder strategySnapshotRecorder;

    @Mock
    private AgentOutcomeRecorder outcomeRecorder;

    private ReActAgentEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ReActAgentEngine();
        ReflectionTestUtils.setField(engine, "deepSeekClient", deepSeekClient);
        ReflectionTestUtils.setField(engine, "promptBuilder", promptBuilder);
        ReflectionTestUtils.setField(engine, "responseParser", responseParser);
        ReflectionTestUtils.setField(engine, "actionExecutor", actionExecutor);
        ReflectionTestUtils.setField(engine, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(engine, "sessionService", sessionService);
        ReflectionTestUtils.setField(engine, "workflowService", workflowService);
        ReflectionTestUtils.setField(engine, "contextMutationService", new AgentContextMutationService());
        ReflectionTestUtils.setField(engine, "agentMetrics", new AgentMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(engine, "medicalSafetyService", new MedicalSafetyService());
        ReflectionTestUtils.setField(engine, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(engine, "strategySnapshotRecorder", strategySnapshotRecorder);
        ReflectionTestUtils.setField(engine, "outcomeRecorder", outcomeRecorder);
        ReflectionTestUtils.setField(engine, "useMock", false);
        ReflectionTestUtils.setField(engine, "protocolRepairAttempts", 1);
    }

    @Test
    void shouldReturnStructuredDegradedResultWhenModelCallFails() {
        ReflectionTestUtils.setField(engine, "maxSteps", 3);
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(List.of());
        when(promptBuilder.buildUserPrompt(anyString(), anyString())).thenReturn("user");
        when(promptBuilder.renderSubAgentSystemPromptFromCatalog(any(), any()))
                .thenReturn(RenderedPrompt.builder().content("system").build());
        when(deepSeekClient.chat(anyString(), anyList())).thenThrow(new RuntimeException("DeepSeek API 调用超时: PT5S"));

        AgentResult result = engine.execute("分析患者减重进展", "C001", "D001");

        assertEquals("ERROR", result.getStatus().name());
        assertEquals("MEDIUM", result.getStructuredFinalAnswer().getRiskLevel());
        assertTrue(result.getStructuredFinalAnswer().getSupportingEvidence().contains("模型调用超时或重试耗尽"));
        assertTrue(result.getStructuredFinalAnswer().getEvidenceSources().contains("SYSTEM_RUNTIME"));
        verify(outcomeRecorder).createOrUpdate(argThat(outcome ->
                outcome != null && outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_RUNTIME));
    }

    @Test
    void shouldReturnStructuredDegradedResultWhenProtocolRepairFails() {
        ReflectionTestUtils.setField(engine, "maxSteps", 3);
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(List.of());
        when(promptBuilder.buildUserPrompt(anyString(), anyString())).thenReturn("user");
        when(promptBuilder.renderSubAgentSystemPromptFromCatalog(any(), any()))
                .thenReturn(RenderedPrompt.builder().content("system").build());
        when(promptBuilder.renderProtocolRepairPrompt(anyString()))
                .thenReturn(RenderedPrompt.builder().content("repair").build());

        DeepSeekClient.DeepSeekResponse invalid = new DeepSeekClient.DeepSeekResponse();
        invalid.setContent("invalid");
        DeepSeekClient.DeepSeekResponse invalid2 = new DeepSeekClient.DeepSeekResponse();
        invalid2.setContent("invalid2");
        when(deepSeekClient.chat(anyString(), anyList())).thenReturn(invalid, invalid2);
        when(responseParser.parse("invalid")).thenThrow(new AgentException("协议解析失败：响应不是合法 JSON"));
        when(responseParser.parse("invalid2")).thenThrow(new AgentException("协议校验失败：finished=true 时 finalAnswer 不能为空"));

        AgentResult result = engine.execute("分析患者减重进展", "C001", "D001");

        assertEquals("ERROR", result.getStatus().name());
        assertTrue(result.getStructuredFinalAnswer().getSummary().contains("模型返回不符合协议"));
        assertTrue(result.getStructuredFinalAnswer().getSupportingEvidence().contains("模型响应协议校验失败"));
        verify(outcomeRecorder).createOrUpdate(argThat(outcome ->
                outcome != null && outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_PROTOCOL_ERROR));
    }

    @Test
    void shouldReturnStructuredDegradedResultWhenMaxStepsReached() {
        ReflectionTestUtils.setField(engine, "maxSteps", 1);
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(List.of());
        when(promptBuilder.buildUserPrompt(anyString(), anyString())).thenReturn("user");
        when(promptBuilder.renderSubAgentSystemPromptFromCatalog(any(), any()))
                .thenReturn(RenderedPrompt.builder().content("system").build());

        DeepSeekClient.DeepSeekResponse step1 = new DeepSeekClient.DeepSeekResponse();
        step1.setContent("step1");
        when(deepSeekClient.chat(anyString(), anyList())).thenReturn(step1);
        when(responseParser.parse("step1")).thenReturn(ThoughtResult.continueWith(
            "继续分析",
            ActionDecision.builder()
                .toolName("get_patient_profile")
                .input(Map.of("customerId", "C001"))
                .reason("获取基础资料")
                .build()
        ));
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(ActionResult.success(Map.of("ok", true)));

        AgentResult result = engine.execute("分析患者减重进展", "C001", "D001");

        assertEquals("MAX_STEPS", result.getStatus().name());
        assertEquals("MEDIUM", result.getStructuredFinalAnswer().getRiskLevel());
        assertTrue(result.getStructuredFinalAnswer().getSupportingEvidence().contains("达到最大步数限制"));
        verify(outcomeRecorder).createOrUpdate(argThat(outcome ->
                outcome != null && outcome.getOutcomeLabel() == AgentOutcomeLabel.FAILED_RUNTIME));
    }

    @Test
    void shouldReturnStructuredHighRiskDegradedResultWhenResumeFails() {
        when(workflowService.loadApprovedResumeState("1", 101L))
            .thenThrow(new AgentException("会话状态不正确，当前状态: RUNNING"));

        AgentResult result = engine.resume("1", 101L);

        assertEquals("ERROR", result.getStatus().name());
        assertEquals("HIGH", result.getStructuredFinalAnswer().getRiskLevel());
        assertTrue(result.getStructuredFinalAnswer().getSupportingEvidence().contains("恢复执行失败"));
    }
}
