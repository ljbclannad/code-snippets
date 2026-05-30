package com.health.agent.engine;

import com.health.agent.client.DeepSeekClient;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.ThoughtResult;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.service.AgentAuditService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReActAgentEngineSafetyTest {

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
    private AgentAuditService agentAuditService;

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
        ReflectionTestUtils.setField(engine, "agentMetrics", new AgentMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(engine, "medicalSafetyService", new MedicalSafetyService());
        ReflectionTestUtils.setField(engine, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(engine, "maxSteps", 3);
        ReflectionTestUtils.setField(engine, "useMock", false);
        ReflectionTestUtils.setField(engine, "protocolRepairAttempts", 1);
    }

    @Test
    void shouldShortCircuitEmergencyGoalBeforeCallingModel() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);

        AgentResult result = engine.execute(
            "患者减重期间出现胸痛和呼吸困难，是否继续当前方案",
            "C001",
            "D001"
        );

        assertEquals("1", result.getSessionId());
        assertEquals("SUCCESS", result.getStatus().name());
        assertTrue(result.getFinalAnswer().contains("立即线下就医"));
        assertEquals("HIGH", result.getStructuredFinalAnswer().getRiskLevel());
        verify(deepSeekClient, never()).chat(anyString(), anyList());
        verify(sessionService).completeSession("1", List.of());
    }

    @Test
    void shouldBlockHighRiskActionWithoutEvidenceBeforeToolExecution() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.getAllTools()).thenReturn(List.of());
        when(promptBuilder.buildSystemPrompt(anyList())).thenReturn("system");
        when(promptBuilder.buildUserPrompt(anyString(), anyString())).thenReturn("user");

        DeepSeekClient.DeepSeekResponse firstResponse = new DeepSeekClient.DeepSeekResponse();
        firstResponse.setContent("r1");
        DeepSeekClient.DeepSeekResponse secondResponse = new DeepSeekClient.DeepSeekResponse();
        secondResponse.setContent("r2");
        when(deepSeekClient.chat(anyString(), anyList())).thenReturn(firstResponse, secondResponse);
        when(responseParser.parse("r1")).thenReturn(ThoughtResult.continueWith(
            "我准备直接给出处方建议",
            ActionDecision.builder()
                .toolName("draft_weight_management_plan")
                .input(Map.of("customerId", "C001", "recommendations", List.of("调整药物")))
                .reason("直接给出处方建议")
                .build()
        ));
        when(responseParser.parse("r2")).thenReturn(ThoughtResult.finished(
            "我先转为保守建议",
            com.health.agent.model.StructuredFinalAnswer.builder()
                .summary("建议先补充近30天体重趋势和生活方式执行情况。")
                .keyFindings(List.of("当前缺少足够证据支持调整方案"))
                .riskLevel("MEDIUM")
                .recommendedActions(List.of("补充体重趋势", "补充饮食运动记录"))
                .needsAttention(true)
                .uncertainties(List.of("尚未获取完整减重执行情况"))
                .build()
        ));

        AgentResult result = engine.execute("分析患者减重进展", "C001", "D001");

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals(2, result.getTotalSteps());
        assertEquals("MEDIUM", result.getStructuredFinalAnswer().getRiskLevel());
        assertTrue(result.getFinalAnswer().contains("问题摘要"));
        verify(actionExecutor, never()).execute(any(), anyString(), anyString(), anyString());
        verify(sessionService).updateSession(anyString(), anyList(), anyList(), anyInt());
    }
}
