package com.health.agent.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.health.agent.client.DeepSeekClient;
import com.health.agent.engine.subagent.CarePlanningSubAgent;
import com.health.agent.engine.subagent.DataCollectionSubAgent;
import com.health.agent.engine.subagent.SubAgentCoordinator;
import com.health.agent.engine.subagent.WeightAnalysisSubAgent;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.AgentStreamEvent;
import com.health.agent.model.AgentStreamEventType;
import com.health.agent.model.PendingTask;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.AgentSessionService;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolRegistry;
import com.health.agent.tool.ToolRiskLevel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@ExtendWith(MockitoExtension.class)
class ReActAgentEngineStreamTest {

    @Mock
    private DeepSeekClient deepSeekClient;

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
        ReflectionTestUtils.setField(engine, "promptBuilder", new ReActPromptBuilder());
        ReflectionTestUtils.setField(engine, "responseParser", new ReActResponseParser());
        ReflectionTestUtils.setField(engine, "actionExecutor", actionExecutor);
        ReflectionTestUtils.setField(engine, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(engine, "sessionService", sessionService);
        ReflectionTestUtils.setField(engine, "agentMetrics", new AgentMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(engine, "medicalSafetyService", new MedicalSafetyService());
        ReflectionTestUtils.setField(engine, "agentAuditService", agentAuditService);
        SubAgentCoordinator coordinator = new SubAgentCoordinator();
        ReflectionTestUtils.setField(coordinator, "dataCollectionSubAgent", new DataCollectionSubAgent());
        ReflectionTestUtils.setField(coordinator, "weightAnalysisSubAgent", new WeightAnalysisSubAgent());
        ReflectionTestUtils.setField(coordinator, "carePlanningSubAgent", new CarePlanningSubAgent());
        ReflectionTestUtils.setField(engine, "subAgentCoordinator", coordinator);
        ReflectionTestUtils.setField(engine, "maxSteps", 4);
        ReflectionTestUtils.setField(engine, "useMock", false);
        ReflectionTestUtils.setField(engine, "protocolRepairAttempts", 1);
    }

    @Test
    void shouldEmitThoughtActionObservationFinalAnswerAndDoneEvents() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.getAllTools()).thenReturn(List.of(
                Tool.builder()
                        .name("detect_weight_plateau")
                        .description("识别是否进入减重停滞期")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("weightRecords", Map.of("type", "array")))
                        .build()));

        DeepSeekClient.DeepSeekResponse first = new DeepSeekClient.DeepSeekResponse();
        first.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "我需要先识别患者是否进入平台期。",
                  "finished": false,
                  "action": {
                    "toolName": "detect_weight_plateau",
                    "input": {
                      "weightRecords": [
                        {"date": "2026-05-30", "weight": 80.0}
                      ]
                    },
                    "reason": "先识别平台期"
                  }
                }
                """);
        DeepSeekClient.DeepSeekResponse second = new DeepSeekClient.DeepSeekResponse();
        second.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "已经有足够证据给出结论。",
                  "finished": true,
                  "finalAnswer": {
                    "summary": "患者近期进入减重停滞期。",
                    "keyFindings": ["近14天体重变化较小"],
                    "riskLevel": "MEDIUM",
                    "recommendedActions": ["安排一次随访沟通"],
                    "needsAttention": true,
                    "uncertainties": ["缺少完整饮食记录"]
                  }
                }
                """);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            DeepSeekClient.DeepSeekResponse response = first;
            sink.accept(response.getContent());
            return response;
        }).doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            DeepSeekClient.DeepSeekResponse response = second;
            sink.accept(response.getContent());
            return response;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString())).thenReturn(
                ActionResult.success(Map.of("isPlateau", true)));

        List<AgentStreamEvent> events = new ArrayList<>();
        engine.executeStream(
                "患者最近两周体重几乎不下降，帮我判断是不是进入平台期并给出建议",
                "C001",
                "D001",
                events::add);

        assertEquals(
                List.of(
                        AgentStreamEventType.SUBAGENT_STARTED,
                        AgentStreamEventType.THINKING_DELTA,
                        AgentStreamEventType.THOUGHT,
                        AgentStreamEventType.ACTION,
                        AgentStreamEventType.CONTEXT_UPDATED,
                        AgentStreamEventType.OBSERVATION,
                        AgentStreamEventType.SUBAGENT_FINISHED,
                        AgentStreamEventType.SUBAGENT_STARTED,
                        AgentStreamEventType.THINKING_DELTA,
                        AgentStreamEventType.THOUGHT,
                        AgentStreamEventType.FINAL_ANSWER,
                        AgentStreamEventType.SUBAGENT_FINISHED,
                        AgentStreamEventType.DONE),
                events.stream().map(AgentStreamEvent::getType).toList());
        assertEquals("1", events.get(0).getSessionId());
        assertInstanceOf(Map.class, events.get(0).getData());
        assertInstanceOf(String.class, events.get(1).getData());
        assertInstanceOf(String.class, events.get(2).getData());
        assertInstanceOf(Map.class, events.get(3).getData());
        assertInstanceOf(Map.class, events.get(4).getData());
        assertInstanceOf(String.class, events.get(5).getData());
        assertTrue(events.get(10).getData().toString().contains("summary"));
    }

    @Test
    void shouldEmitPendingApprovalThenDoneWhenActionNeedsApproval() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.getAllTools()).thenReturn(List.of(
                Tool.builder()
                        .name("create_follow_up_task")
                        .description("创建随访任务")
                        .riskLevel(ToolRiskLevel.MEDIUM)
                        .inputSchema(Map.of("customerId", Map.of("type", "string"), "title", Map.of("type", "string")))
                        .build()));

        DeepSeekClient.DeepSeekResponse first = new DeepSeekClient.DeepSeekResponse();
        first.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "我需要创建一个随访任务。",
                  "finished": false,
                  "action": {
                    "toolName": "create_follow_up_task",
                    "input": {
                      "customerId": "C001",
                      "title": "安排减重随访"
                    },
                    "reason": "创建后续随访任务"
                  }
                }
                """);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            sink.accept(first.getContent());
            return first;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString())).thenReturn(
                ActionResult.pendingApproval(PendingTask.builder().id(101L).toolName("create_follow_up_task").build()));

        List<AgentStreamEvent> events = new ArrayList<>();
        engine.executeStream("帮我安排一次减重随访任务", "C001", "D001", events::add);

        assertEquals(
                List.of(
                        AgentStreamEventType.SUBAGENT_STARTED,
                        AgentStreamEventType.THINKING_DELTA,
                        AgentStreamEventType.THOUGHT,
                        AgentStreamEventType.ACTION,
                        AgentStreamEventType.PENDING_APPROVAL,
                        AgentStreamEventType.SUBAGENT_FINISHED,
                        AgentStreamEventType.DONE),
                events.stream().map(AgentStreamEvent::getType).toList());
        assertInstanceOf(PendingTask.class, events.get(4).getData());
    }
}
