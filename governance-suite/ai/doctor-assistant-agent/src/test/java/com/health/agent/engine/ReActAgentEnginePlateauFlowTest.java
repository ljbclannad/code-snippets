package com.health.agent.engine;

import com.health.agent.client.DeepSeekClient;
import com.health.agent.learning.enums.AgentOutcomeLabel;
import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.model.AgentRunStrategySnapshot;
import com.health.agent.learning.service.AgentOutcomeRecorder;
import com.health.agent.learning.service.AgentRunStrategySnapshotRecorder;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.engine.subagent.CarePlanningSubAgent;
import com.health.agent.engine.subagent.DataCollectionSubAgent;
import com.health.agent.engine.subagent.RiskTriageSubAgent;
import com.health.agent.engine.subagent.SubAgentCoordinator;
import com.health.agent.engine.subagent.WeightAnalysisSubAgent;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.ToolExecutionOutput;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.AgentContextMutationService;
import com.health.agent.service.AgentSessionService;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolRegistry;
import com.health.agent.tool.ToolRiskLevel;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentStatus;
import com.lx.governance.agentplatform.model.AgentType;
import com.lx.governance.agentplatform.model.ToolDescriptor;
import com.lx.governance.agentplatform.registry.InMemoryAgentRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;

@ExtendWith(MockitoExtension.class)
class ReActAgentEnginePlateauFlowTest {

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

    @Mock
    private AgentRunStrategySnapshotRecorder strategySnapshotRecorder;

    @Mock
    private AgentOutcomeRecorder outcomeRecorder;

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
        ReflectionTestUtils.setField(engine, "contextMutationService", new AgentContextMutationService());
        ReflectionTestUtils.setField(engine, "agentMetrics", new AgentMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(engine, "medicalSafetyService", new MedicalSafetyService());
        ReflectionTestUtils.setField(engine, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(engine, "strategySnapshotRecorder", strategySnapshotRecorder);
        ReflectionTestUtils.setField(engine, "outcomeRecorder", outcomeRecorder);
        InMemoryAgentRegistry agentRegistry = new InMemoryAgentRegistry();
        List<com.health.agent.engine.subagent.AgentSubAgent> subAgents = List.of(
                new DataCollectionSubAgent(),
                new WeightAnalysisSubAgent(),
                new CarePlanningSubAgent(),
                new RiskTriageSubAgent());
        subAgents.forEach(subAgent -> register(agentRegistry, subAgent));
        SubAgentCoordinator coordinator = new SubAgentCoordinator(agentRegistry, subAgents);
        ReflectionTestUtils.setField(engine, "subAgentCoordinator", coordinator);
        ReflectionTestUtils.setField(engine, "maxSteps", 4);
        ReflectionTestUtils.setField(engine, "useMock", false);
        ReflectionTestUtils.setField(engine, "protocolRepairAttempts", 1);
    }

    private void register(
            InMemoryAgentRegistry agentRegistry,
            com.health.agent.engine.subagent.AgentSubAgent subAgent) {
        agentRegistry.register(AgentDefinition.builder()
                .agentId(subAgent.agentId())
                .displayName(subAgent.name())
                .agentType(AgentType.SUB_AGENT)
                .capabilities(capabilitiesOf(subAgent.name()))
                .supportedTaskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS.name())
                .supportedTaskType(TaskType.RISK_STRATIFICATION_AND_ESCALATION.name())
                .supportedTaskType(TaskType.NEXT_FOLLOW_UP_ACTION_RECOMMENDATION.name())
                .allowedTools(subAgent.preferredToolNames())
                .riskLevel("MEDIUM")
                .owner("test")
                .version("v1")
                .status(AgentStatus.ENABLED)
                .build());
    }

    private List<String> capabilitiesOf(String subAgentName) {
        return switch (subAgentName) {
            case "DataCollectionAgent" -> List.of(SubAgentCoordinator.CAPABILITY_DATA_COLLECTION);
            case "WeightAnalysisAgent" -> List.of(SubAgentCoordinator.CAPABILITY_WEIGHT_ANALYSIS);
            case "CarePlanningAgent" -> List.of(SubAgentCoordinator.CAPABILITY_CARE_PLANNING);
            case "RiskTriageAgent" -> List.of(SubAgentCoordinator.CAPABILITY_RISK_TRIAGE);
            default -> List.of();
        };
    }

    private List<ToolDescriptor> descriptorsOf(List<Tool> tools) {
        return tools.stream()
                .map(tool -> ToolDescriptor.builder()
                        .toolName(tool.getName())
                        .description(tool.getDescription())
                        .riskLevel(tool.getRiskLevel() != null ? tool.getRiskLevel().name() : "UNKNOWN")
                        .inputSchema(tool.getInputSchema())
                        .build())
                .toList();
    }

    @Test
    void shouldTriggerDetectWeightPlateauForPlateauGoal() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors()).thenReturn(descriptorsOf(List.of(
            Tool.builder()
                .name("detect_weight_plateau")
                .description("识别是否进入减重停滞期")
                .riskLevel(ToolRiskLevel.LOW)
                .inputSchema(Map.of("weightRecords", Map.of("type", "array")))
                .build()
        )));

        DeepSeekClient.DeepSeekResponse first = new DeepSeekClient.DeepSeekResponse();
        first.setContent("""
            {
              "protocolVersion": "v1",
              "thinking": "患者近两周减重停滞，我需要先判断是否进入平台期。",
              "finished": false,
              "action": {
                "toolName": "detect_weight_plateau",
                "input": {
                  "weightRecords": [
                    {"date": "2026-05-30", "weight": 80.0},
                    {"date": "2026-05-28", "weight": 80.1},
                    {"date": "2026-05-25", "weight": 79.9},
                    {"date": "2026-05-22", "weight": 80.2},
                    {"date": "2026-05-19", "weight": 80.1},
                    {"date": "2026-05-16", "weight": 80.3}
                  ]
                },
                "reason": "先识别是否进入减重停滞期"
              }
            }
            """);
        DeepSeekClient.DeepSeekResponse second = new DeepSeekClient.DeepSeekResponse();
        second.setContent("""
            {
              "protocolVersion": "v1",
              "thinking": "已经确认进入停滞期，可以给出随访建议。",
              "finished": true,
              "finalAnswer": {
                "summary": "患者近两周已进入减重停滞期。",
                "keyFindings": ["近14天体重变化小于0.5kg", "最近7天没有明显下降趋势"],
                "riskLevel": "MEDIUM",
                "recommendedActions": ["复核饮食记录", "加强运动执行", "安排一次随访沟通"],
                "needsAttention": true,
                "uncertainties": ["缺少完整饮食依从性记录"]
              }
            }
            """);
        when(deepSeekClient.chat(anyString(), anyList())).thenReturn(first, second);
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString(), any())).thenReturn(
            ActionResult.success(ToolExecutionOutput.builder()
                .data(Map.of(
                    "isPlateau", true,
                    "recentTrend", "PLATEAU",
                    "reason", "近两周体重变化较小，且最近一周未出现明显下降趋势。"
                ))
                .summary("已完成平台期判断")
                .stateUpdates(Map.of(
                    "plateau_assessment", Map.of(
                        "isPlateau", true,
                        "recentTrend", "PLATEAU",
                        "reason", "近两周体重变化较小，且最近一周未出现明显下降趋势。"
                    )
                ))
                .build()
            )
        );

        AgentResult result = engine.execute(
            "患者最近两周体重几乎不下降，帮我判断是不是进入平台期并给出建议",
            "C001",
            "D001"
        );

        assertEquals("SUCCESS", result.getStatus().name());
        assertEquals("MEDIUM", result.getStructuredFinalAnswer().getRiskLevel());
        assertTrue(result.getFinalAnswer().contains("停滞期"));

        ArgumentCaptor<com.health.agent.model.ActionDecision> actionCaptor =
            ArgumentCaptor.forClass(com.health.agent.model.ActionDecision.class);
        verify(actionExecutor).execute(actionCaptor.capture(), anyString(), anyString(), anyString(), any());
        assertEquals("detect_weight_plateau", actionCaptor.getValue().getToolName());

        ArgumentCaptor<AgentContext> contextCaptor = ArgumentCaptor.forClass(AgentContext.class);
        verify(sessionService).updateSession(anyString(), anyList(), anyList(), contextCaptor.capture(), anyInt());
        assertTrue(contextCaptor.getValue().getSlots().containsKey("plateau_assessment"));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(deepSeekClient, org.mockito.Mockito.times(2)).chat(promptCaptor.capture(), anyList());
        assertTrue(promptCaptor.getAllValues().get(0).contains("DataCollectionAgent"));
        assertTrue(promptCaptor.getAllValues().get(1).contains("CarePlanningAgent"));
        ArgumentCaptor<AgentRunOutcome> outcomeCaptor = ArgumentCaptor.forClass(AgentRunOutcome.class);
        verify(outcomeRecorder).createOrUpdate(outcomeCaptor.capture());
        assertEquals(AgentOutcomeLabel.SUCCESS, outcomeCaptor.getValue().getOutcomeLabel());
        ArgumentCaptor<AgentRunStrategySnapshot> snapshotCaptor = ArgumentCaptor.forClass(AgentRunStrategySnapshot.class);
        verify(strategySnapshotRecorder, atLeastOnce()).createOrUpdate(snapshotCaptor.capture());
        assertTrue(snapshotCaptor.getAllValues().stream()
                .anyMatch(snapshot -> snapshot.getSelectedAgentIds() != null
                        && snapshot.getSelectedAgentIds().contains("doctor.DataCollectionAgent")));
        assertTrue(snapshotCaptor.getAllValues().stream()
                .anyMatch(snapshot -> snapshot.getToolSequence() != null
                        && snapshot.getToolSequence().contains("detect_weight_plateau")));
        assertTrue(snapshotCaptor.getAllValues().stream()
                .anyMatch(snapshot -> "doctor-react-prompt-v1".equals(snapshot.getPromptVersion())));
    }
}
