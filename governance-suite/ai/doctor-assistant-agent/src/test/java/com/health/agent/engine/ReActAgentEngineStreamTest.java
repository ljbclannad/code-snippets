package com.health.agent.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.alibaba.fastjson2.JSON;
import com.health.agent.client.DeepSeekClient;
import com.health.agent.config.DoctorSafetyProperties;
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.patient.AgentTaskConstraints;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.engine.subagent.CarePlanningSubAgent;
import com.health.agent.engine.subagent.DataCollectionSubAgent;
import com.health.agent.engine.subagent.RiskTriageSubAgent;
import com.health.agent.engine.subagent.SubAgentCoordinator;
import com.health.agent.engine.subagent.WeightAnalysisSubAgent;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.AgentStreamEvent;
import com.health.agent.model.AgentStreamEventType;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.Step;
import com.health.agent.model.TaskStatus;
import com.health.agent.model.ToolExecutionOutput;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.AgentApprovalResumeState;
import com.health.agent.service.AgentContextMutationService;
import com.health.agent.service.AgentSessionWorkflowService;
import com.health.agent.service.AgentSessionService;
import com.health.agent.service.ContextPrivacySanitizer;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolRegistry;
import com.health.agent.tool.ToolRiskLevel;
import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentStatus;
import com.lx.governance.agentplatform.model.AgentType;
import com.lx.governance.agentplatform.model.PlatformAgentHandoff;
import com.lx.governance.agentplatform.model.PlatformContextEvidence;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import com.lx.governance.agentplatform.model.ToolDescriptor;
import com.lx.governance.agentplatform.registry.InMemoryAgentRegistry;

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
    private AgentSessionWorkflowService workflowService;

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
        ReflectionTestUtils.setField(engine, "workflowService", workflowService);
        ReflectionTestUtils.setField(engine, "contextMutationService", new AgentContextMutationService());
        ReflectionTestUtils.setField(engine, "agentMetrics", new AgentMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(engine, "medicalSafetyService", new MedicalSafetyService());
        ReflectionTestUtils.setField(engine, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(engine, "contextPrivacySanitizer",
                new ContextPrivacySanitizer(new DoctorSafetyProperties()));
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
    void shouldEmitThoughtActionObservationFinalAnswerAndDoneEvents() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(descriptorsOf(List.of(
                Tool.builder()
                        .name("detect_weight_plateau")
                        .description("识别是否进入减重停滞期")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("weightRecords", Map.of("type", "array")))
                        .build())));

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
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString(), any())).thenReturn(
                ActionResult.success(Map.of("isPlateau", true)));

        List<AgentStreamEvent> events = new ArrayList<>();
        engine.executeStream(
                "患者最近两周体重几乎不下降，帮我判断是不是进入平台期并给出建议",
                "C001",
                "D001",
                events::add);

        List<AgentStreamEventType> eventTypes = events.stream().map(AgentStreamEvent::getType).toList();
        assertEquals(AgentStreamEventType.SUBAGENT_STARTED, eventTypes.get(0));
        assertEquals(AgentStreamEventType.DONE, eventTypes.get(eventTypes.size() - 1));
        assertTrue(eventTypes.contains(AgentStreamEventType.THINKING_DELTA));
        assertTrue(eventTypes.contains(AgentStreamEventType.THOUGHT));
        assertTrue(eventTypes.contains(AgentStreamEventType.ACTION));
        assertTrue(eventTypes.contains(AgentStreamEventType.CONTEXT_UPDATED));
        assertTrue(eventTypes.contains(AgentStreamEventType.OBSERVATION));
        assertTrue(eventTypes.contains(AgentStreamEventType.FINAL_ANSWER));
        assertTrue(eventTypes.contains(AgentStreamEventType.SUBAGENT_FINISHED));
        assertEquals("1", events.get(0).getSessionId());
        assertInstanceOf(Map.class, events.get(0).getData());
        @SuppressWarnings("unchecked")
        Map<String, Object> subAgentStarted = (Map<String, Object>) events.get(0).getData();
        assertInstanceOf(PlatformAgentHandoff.class, subAgentStarted.get("handoff"));
        PlatformAgentHandoff handoff = (PlatformAgentHandoff) subAgentStarted.get("handoff");
        assertNotNull(handoff.getToAgentName());
        assertTrue(handoff.getItems().isEmpty() || handoff.getItems().size() <= 11);
        assertTrue(handoff.getItems().stream()
                .noneMatch(item -> "trigger_decision".equals(item.getKey())));
        @SuppressWarnings("unchecked")
        Map<String, Object> profilePayload = handoff.getItems().stream()
                .filter(item -> "patient_profile".equals(item.getKey()))
                .findFirst()
                .map(item -> (Map<String, Object>) item.getPayload())
                .orElse(Map.of());
        if (!profilePayload.isEmpty()) {
            assertEquals("张*", profilePayload.get("name"));
        }
        assertInstanceOf(String.class, events.get(1).getData());
        assertInstanceOf(String.class, events.get(2).getData());
        assertInstanceOf(Map.class, events.get(3).getData());
        assertInstanceOf(Map.class, events.get(4).getData());
        assertInstanceOf(String.class, events.get(5).getData());
        AgentStreamEvent finalAnswerEvent = events.stream()
                .filter(event -> event.getType() == AgentStreamEventType.FINAL_ANSWER)
                .findFirst()
                .orElseThrow();
        assertTrue(finalAnswerEvent.getData().toString().contains("summary"));
    }

    @Test
    void shouldEmitPendingApprovalThenDoneWhenActionNeedsApproval() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(descriptorsOf(List.of(
                Tool.builder()
                        .name("create_follow_up_task")
                        .description("创建随访任务")
                        .riskLevel(ToolRiskLevel.MEDIUM)
                        .inputSchema(Map.of("customerId", Map.of("type", "string"), "title", Map.of("type", "string")))
                        .build())));

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
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString(), any())).thenReturn(
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

    @Test
    void shouldConsumeParallelSelectionPlanAcrossLoopSteps() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString(), anyString())).thenReturn(session);
        AtomicReference<AgentContext> finalContextRef = new AtomicReference<>();
        doAnswer(invocation -> {
            finalContextRef.set(invocation.getArgument(2));
            return null;
        }).when(sessionService).completeSession(anyString(), anyList(), any(AgentContext.class));
        when(toolRegistry.listToolDescriptors(any())).thenReturn(descriptorsOf(List.of(
                Tool.builder()
                        .name("collect_medical_history")
                        .description("收集病史")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("question", Map.of("type", "string")))
                        .build())));

        DeepSeekClient.DeepSeekResponse first = new DeepSeekClient.DeepSeekResponse();
        first.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "先由风险分诊角色评估胸痛与呼吸困难的紧急程度。",
                  "finished": false,
                  "action": {
                    "toolName": "collect_medical_history",
                    "input": {
                      "question": "请补充胸痛持续时间和伴随症状"
                    },
                    "reason": "先完成初步风险识别"
                  }
                }
                """);
        DeepSeekClient.DeepSeekResponse second = new DeepSeekClient.DeepSeekResponse();
        second.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "接着由资料收集角色补齐随访信息。",
                  "finished": false,
                  "action": {
                    "toolName": "collect_medical_history",
                    "input": {
                      "question": "请补充近期用药与体重变化"
                    },
                    "reason": "补足后续随访与风险判断所需资料"
                  }
                }
                """);
        DeepSeekClient.DeepSeekResponse third = new DeepSeekClient.DeepSeekResponse();
        third.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "已经完成首轮风险分诊与资料补齐。",
                  "finished": true,
                  "finalAnswer": {
                    "summary": "患者存在胸痛与呼吸困难，建议尽快人工复核，并继续补充近期用药和体重变化。",
                    "keyFindings": ["存在胸痛和呼吸困难", "首轮资料补齐已启动"],
                    "riskLevel": "HIGH",
                    "recommendedActions": ["尽快安排人工复核", "继续补充近期用药和体重变化"],
                    "needsAttention": true,
                    "uncertainties": ["尚缺完整生命体征和近期药物依从性"]
                  }
                }
                """);
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            sink.accept(first.getContent());
            return first;
        }).doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            sink.accept(second.getContent());
            return second;
        }).doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            sink.accept(third.getContent());
            return third;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(ActionResult.success(Map.of("ok", true)));

        AgentExecutionCommand command = AgentExecutionCommand.builder()
                .customerId("C001")
                .doctorId("D001")
                .goal("请完成本轮风险分层并补充关键随访信息")
                .patientMessage("这两天胸痛并伴随呼吸困难")
                .taskType(TaskType.RISK_STRATIFICATION_AND_ESCALATION)
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .conversationId("conv-risk-1")
                .correlationId("corr-risk-1")
                .sessionContext(Map.of("selectorRollout", Map.of("parallelRiskReview", true)))
                .build();

        List<AgentStreamEvent> events = new ArrayList<>();
        AgentResult result = engine.executeStream(command, events::add);

        assertEquals("SUCCESS", result.getStatus().name());
        AgentContext finalContext = finalContextRef.get();
        assertNotNull(finalContext);
        @SuppressWarnings("unchecked")
        List<String> executedPlan = (List<String>) finalContext.getSlots()
                .get("sub_agent_executed_plan")
                .getValue();
        @SuppressWarnings("unchecked")
        List<String> pendingPlan = (List<String>) finalContext.getSlots()
                .get("sub_agent_pending_plan")
                .getValue();
        @SuppressWarnings("unchecked")
        Map<String, Object> selectionDecision = (Map<String, Object>) finalContext.getSlots()
                .get("sub_agent_selection_decision")
                .getValue();
        assertEquals("PARALLEL", selectionDecision.get("mode"));
        assertTrue(executedPlan.contains("doctor.RiskTriageAgent"));
        assertTrue(executedPlan.contains("doctor.DataCollectionAgent"));
        assertTrue(pendingPlan == null || pendingPlan.isEmpty());
        List<String> startedNames = events.stream()
                .filter(event -> event.getType() == AgentStreamEventType.SUBAGENT_STARTED)
                .map(event -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) event.getData();
                    return String.valueOf(data.get("name"));
                })
                .toList();
        assertFalse(startedNames.isEmpty());
    }

    @Test
    void shouldPreloadContextFromAgentExecutionCommand() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(descriptorsOf(List.of(
                Tool.builder()
                        .name("detect_weight_plateau")
                        .description("识别是否进入减重停滞期")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("weightRecords", Map.of("type", "array")))
                        .build())));

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
                    "uncertainties": ["缺少完整饮食记录"],
                    "supportingEvidence": ["WEIGHT_HISTORY", "SESSION_CONTEXT"],
                    "evidenceSources": ["AI_CHAT_MEMORY_SNAPSHOT", "AI_CHAT_SESSION_CONTEXT"],
                    "inferences": ["适合继续随访分析"]
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
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString(), any())).thenReturn(
                ActionResult.success(Map.of("isPlateau", true)));

        AtomicReference<AgentContext> capturedContext = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedContext.set(invocation.getArgument(3));
            return null;
        }).when(sessionService).updateSession(anyString(), anyList(), anyList(), any(AgentContext.class), any(Integer.class));

        AgentExecutionCommand command = AgentExecutionCommand.builder()
                .goal("患者最近两周体重几乎不下降，帮我判断是不是进入平台期并给出建议")
                .customerId("C001")
                .doctorId("D001")
                .conversationId("conv-100")
                .correlationId("corr-100")
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .patientMessage("最近两周体重几乎不下降")
                .sessionContext(Map.of("conversationPhase", "follow_up"))
                .profileSnapshot(Map.of("age", 32))
                .memorySnapshot(MemorySnapshot.builder()
                        .shortTermMemories(List.of("近3天体重变化小"))
                        .longTermMemories(List.of("既往晚间加餐"))
                        .build())
                .triggerDecision(Map.of("summary", "命中升级"))
                .constraints(AgentTaskConstraints.builder().requireEvidence(Boolean.TRUE).build())
                .build();

        engine.executeStream(command, event -> {
        });

        AgentContext context = capturedContext.get();
        assertNotNull(context);
        assertTrue(context.getSlots().containsKey("patient_task_request"));
        assertTrue(context.getSlots().containsKey("session_context"));
        assertTrue(context.getSlots().containsKey("profile_snapshot"));
        assertTrue(context.getSlots().containsKey("patient_profile"));
        assertTrue(context.getSlots().containsKey("memory_snapshot"));
        assertTrue(context.getSlots().containsKey("chat_history"));
        assertTrue(context.getSlots().containsKey("recent_follow_up_signals"));
        assertTrue(context.getSlots().containsKey("trigger_decision"));
        assertEquals(PlatformContextVisibility.AUDIT_ONLY,
                context.getSlots().get("trigger_decision").getVisibility());
        assertTrue(context.getSlots().containsKey("patient_message"));
        assertTrue(context.getEvidenceRecords().stream()
                .anyMatch(record -> "PATIENT_MESSAGE".equals(record.getSource())
                        && "最近两周体重几乎不下降".equals(record.getSummary())));
    }

    @Test
    void shouldBuildPatientTaskSpecificFirstTurnPromptFromCommand() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(descriptorsOf(List.of(
                Tool.builder()
                        .name("detect_weight_plateau")
                        .description("识别是否进入减重停滞期")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("weightRecords", Map.of("type", "array")))
                        .build())));

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
        AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();
        DeepSeekClient.DeepSeekResponse response = new DeepSeekClient.DeepSeekResponse();
        response.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "已有足够的上游快照，可以先结束。",
                  "finished": true,
                  "finalAnswer": {
                    "summary": "已基于首轮快照完成分析。",
                    "keyFindings": ["已收到患者当前消息和记忆快照"],
                    "riskLevel": "LOW",
                    "recommendedActions": ["继续随访"],
                    "needsAttention": false,
                    "uncertainties": [],
                    "supportingEvidence": ["PATIENT_MESSAGE", "MEMORY_SNAPSHOT"],
                    "evidenceSources": ["AI_CHAT_SESSION_CONTEXT", "AI_CHAT_MEMORY"],
                    "inferences": []
                  }
                }
                """);
        doAnswer(invocation -> {
            capturedSystemPrompt.set(invocation.getArgument(0));
            @SuppressWarnings("unchecked")
            List<Message> messages = invocation.getArgument(1);
            capturedMessages.set(new ArrayList<>(messages));
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            sink.accept(response.getContent());
            return response;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());

        AgentExecutionCommand command = AgentExecutionCommand.builder()
                .goal("患者反馈最近一周晚餐控制后体重仍无明显下降，请判断是否进入平台期并给出下一步随访建议")
                .customerId("C10001")
                .doctorId("D20001")
                .conversationId("conv_20260616_001")
                .correlationId("corr_20260616_001")
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .patientMessage("医生，我这周晚上基本没吃主食，但体重还是没怎么掉，可以回电13812345678吗？"
                        + "我会继续记录。".repeat(80) + "这是消息末尾的新信息。")
                .sessionContext(Map.of(
                        "conversationPhase", "follow_up",
                        "currentTopic", "weight_plateau"))
                .profileSnapshot(Map.of(
                        "name", "张三",
                        "gender", "female",
                        "age", 32,
                        "phone", "13812345678",
                        "currentWeightKg", 68.5))
                .memorySnapshot(MemorySnapshot.builder()
                        .shortTermMemories(List.of(
                                "近7天体重在68.4kg到68.7kg之间波动，可联系13812345678回访",
                                "患者自述晚餐减少主食摄入"))
                        .longTermMemories(List.of(
                                "3个月前开始减重管理",
                                "既往存在晚间加餐习惯"))
                        .build())
                .triggerDecision(Map.of(
                        "summary", "命中随访趋势分析信号",
                        "reasons", List.of("EXPLICIT_ANALYSIS_REQUEST", "TREND_ANALYSIS_REQUEST")))
                .constraints(AgentTaskConstraints.builder().requireEvidence(Boolean.TRUE).build())
                .build();

        engine.executeStream(command, event -> {
        });

        List<Message> firstTurnMessages = capturedMessages.get();
        assertNotNull(firstTurnMessages);
        assertEquals(1, firstTurnMessages.size());
        String firstPrompt = firstTurnMessages.get(0).getContent();
        String systemPrompt = capturedSystemPrompt.get();
        assertTrue(firstPrompt.contains("Task Type: PATIENT_FOLLOW_UP_ANALYSIS"));
        assertTrue(firstPrompt.contains("Task-specific Analysis Focus"));
        assertTrue(firstPrompt.contains("平台期风险"));
        assertFalse(firstPrompt.contains("医生，我这周晚上"));
        assertFalse(firstPrompt.contains("68.4kg"));
        assertFalse(firstPrompt.contains("female"));
        assertFalse(firstPrompt.contains("weight_plateau"));
        assertNotNull(systemPrompt);
        assertTrue(systemPrompt.contains("Current Patient Message:\n\"医生"));
        assertTrue(systemPrompt.contains("这是消息末尾的新信息。"));
        assertTrue(systemPrompt.contains("Patient Profile Facts"));
        assertTrue(systemPrompt.contains("Session Context Summary"));
        assertTrue(systemPrompt.contains("Long-term Memory"));
        assertTrue(systemPrompt.contains("Short-term Memory"));
        assertTrue(systemPrompt.contains("近7天体重在68.4kg到68.7kg之间波动"));
        assertEquals(1, systemPrompt.split("近7天体重在68.4kg到68.7kg之间波动", -1).length - 1);
        assertTrue(systemPrompt.contains("female"));
        assertTrue(systemPrompt.contains("张*"));
        assertTrue(systemPrompt.contains("138****5678"));
        assertFalse(systemPrompt.contains("张三"));
        assertFalse(systemPrompt.contains("13812345678"));
        assertFalse(systemPrompt.contains("命中随访趋势分析信号"));
        assertFalse(systemPrompt.contains("EXPLICIT_ANALYSIS_REQUEST"));
    }

    @Test
    void resumeStreamShouldUpdateContextAndEmitContextUpdatedEventAfterApprovedAction() {
        AgentContext persistedContext = AgentContext.initialize("C001", "D001", "恢复随访分析");
        persistedContext.putSlot("session_context", Map.of("groupId", "pilot", "tenantId", "tenant-a"), "TEST");
        var oldRecords = List.of(Map.of("date", "2026-05-18", "weight", 69.4));
        String oldObservation = ReActPromptHistory.OBSERVATION_PREFIX + JSON.toJSONString(PlatformToolOutput.builder()
                .toolName("get_weight_history")
                .data(oldRecords)
                .stateUpdates(Map.of("weight_history", oldRecords))
                .evidence(PlatformContextEvidence.builder().source("WEIGHT_HISTORY").payload(oldRecords).build())
                .build());
        List<Message> persistedMessages = new ArrayList<>(
                List.of(Message.user("请继续分析"), Message.user(oldObservation)));
        AgentSession session = AgentSession.builder()
                .id("1")
                .customerId("C001")
                .doctorId("D001")
                .goal("恢复随访分析")
                .status(SessionStatus.PAUSED.name())
                .historyJson(JSON.toJSONString(List.of(Step.thought("等待审批"))))
                .messagesJson(JSON.toJSONString(persistedMessages))
                .contextJson(JSON.toJSONString(persistedContext))
                .currentStep(1)
                .build();
        ActionDecision approvedAction = ActionDecision.builder()
                .toolName("get_weight_history")
                .input(Map.of("customerId", "C001", "days", 14))
                .reason("审批后继续获取体重历史")
                .build();
        PendingTask approvedTask = PendingTask.builder()
                .id(101L)
                .agentSessionId("1")
                .customerId("C001")
                .doctorId("D001")
                .status(TaskStatus.APPROVED.name())
                .inputJson(JSON.toJSONString(approvedAction))
                .build();
        when(workflowService.loadApprovedResumeState("1", 101L)).thenReturn(AgentApprovalResumeState.builder()
                .session(session)
                .pendingTask(approvedTask)
                .history(new ArrayList<>(List.of(Step.thought("等待审批"))))
                .messages(persistedMessages)
                .context(persistedContext)
                .currentStep(1)
                .approvedAction(approvedAction)
                .build());

        ActionResult approvedOutput = ActionResult.success(
                ToolExecutionOutput.builder()
                        .summary("已获取最近14天体重历史")
                        .stateUpdates(Map.of("weight_history", List.of(
                                Map.of("date", "2026-06-01", "weight", 68.5)
                        )))
                        .build());
        when(actionExecutor.executeApprovedAction(any())).thenReturn(approvedOutput);

        when(toolRegistry.listToolDescriptors(any())).thenReturn(descriptorsOf(List.of(
                Tool.builder()
                        .name("get_weight_history")
                        .description("查询体重历史")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("customerId", Map.of("type", "string")))
                        .build())));

        DeepSeekClient.DeepSeekResponse finalResponse = new DeepSeekClient.DeepSeekResponse();
        finalResponse.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "已有批准后的体重历史，可以结束本轮分析。",
                  "finished": true,
                  "finalAnswer": {
                    "summary": "已恢复执行并补齐体重历史。",
                    "keyFindings": ["近14天体重历史已补齐"],
                    "riskLevel": "LOW",
                    "recommendedActions": ["继续随访观察"],
                    "needsAttention": false,
                    "uncertainties": [],
                    "supportingEvidence": ["已获取最近14天体重历史"],
                    "evidenceSources": ["WEIGHT_HISTORY"],
                    "inferences": []
                  }
                }
                """);
        AtomicReference<List<Message>> modelMessages = new AtomicReference<>();
        doAnswer(invocation -> {
            modelMessages.set(invocation.getArgument(1));
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            sink.accept(finalResponse.getContent());
            return finalResponse;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());

        List<AgentStreamEvent> events = new ArrayList<>();
        assertDoesNotThrow(() -> engine.resumeStream("1", 101L, events::add));
        verify(actionExecutor).executeApprovedAction(approvedAction);
        verify(toolRegistry).listToolDescriptors(AgentAccessContext.builder()
                .groupId("pilot").userId("D001").tenantId("tenant-a").build());
        assertEquals(oldObservation, persistedMessages.get(1).getContent());
        String compact = modelMessages.get().get(1).getContent();
        assertTrue(compact.length() < oldObservation.length());
        assertTrue(compact.contains("2026-05-18"));
        assertTrue(compact.contains("WEIGHT_HISTORY"));
        assertEquals(ReActPromptHistory.OBSERVATION_PREFIX + new AgentContextMutationService().formatObservation(approvedOutput),
                modelMessages.get().get(2).getContent());

        List<AgentStreamEventType> eventTypes = events.stream().map(AgentStreamEvent::getType).toList();
        assertTrue(eventTypes.contains(AgentStreamEventType.OBSERVATION));
        assertTrue(eventTypes.contains(AgentStreamEventType.CONTEXT_UPDATED));

        AgentStreamEvent contextUpdatedEvent = events.stream()
                .filter(event -> event.getType() == AgentStreamEventType.CONTEXT_UPDATED)
                .findFirst()
                .orElseThrow();
        assertInstanceOf(Map.class, contextUpdatedEvent.getData());
        @SuppressWarnings("unchecked")
        Map<String, Object> contextUpdatedData = (Map<String, Object>) contextUpdatedEvent.getData();
        assertTrue(contextUpdatedData.containsKey("slots"));
        assertTrue(String.valueOf(contextUpdatedData.get("reason")).contains("get_weight_history"));
        assertTrue(String.valueOf(contextUpdatedData.get("slots")).contains("weight_history"));
        verify(agentAuditService).recordResumeContextUpdate(
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.eq("C001"),
                org.mockito.ArgumentMatchers.eq("D001"),
                org.mockito.ArgumentMatchers.eq("恢复随访分析"),
                org.mockito.ArgumentMatchers.eq("get_weight_history"),
                any(),
                any());
    }

    @Test
    void shouldSuppressDuplicateToolCallWhenSameActionRepeatsWithoutNewInformation() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.listToolDescriptors(any())).thenReturn(descriptorsOf(List.of(
                Tool.builder()
                        .name("detect_weight_plateau")
                        .description("识别是否进入减重停滞期")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("weightRecords", Map.of("type", "array")))
                        .build())));

        DeepSeekClient.DeepSeekResponse first = new DeepSeekClient.DeepSeekResponse();
        first.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "我先识别平台期。",
                  "finished": false,
                  "action": {
                    "toolName": "detect_weight_plateau",
                    "input": {"weightRecords": [{"date": "2026-05-30", "weight": 80.0}]},
                    "reason": "识别平台期"
                  }
                }
                """);
        DeepSeekClient.DeepSeekResponse second = new DeepSeekClient.DeepSeekResponse();
        second.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "我再调用一次同样的工具。",
                  "finished": false,
                  "action": {
                    "toolName": "detect_weight_plateau",
                    "input": {"weightRecords": [{"date": "2026-05-30", "weight": 80.0}]},
                    "reason": "再次识别平台期"
                  }
                }
                """);
        DeepSeekClient.DeepSeekResponse third = new DeepSeekClient.DeepSeekResponse();
        third.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "已有足够信息结束。",
                  "finished": true,
                  "finalAnswer": {
                    "summary": "已阻止重复调用并保守收敛。",
                    "keyFindings": ["同一工具重复调用未引入新信息"],
                    "riskLevel": "LOW",
                    "recommendedActions": ["基于现有证据继续随访"],
                    "needsAttention": false,
                    "uncertainties": [],
                    "supportingEvidence": ["重复调用已被抑制"],
                    "evidenceSources": ["SYSTEM_RUNTIME"],
                    "inferences": []
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
        }).doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            DeepSeekClient.DeepSeekResponse response = third;
            sink.accept(response.getContent());
            return response;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString(), any())).thenReturn(
                ActionResult.success(Map.of("isPlateau", true)));

        List<AgentStreamEvent> events = new ArrayList<>();
        engine.executeStream("判断患者是否进入平台期", "C001", "D001", events::add);

        long actionEvents = events.stream().filter(event -> event.getType() == AgentStreamEventType.ACTION).count();
        assertEquals(2, actionEvents);
        long observationEvents = events.stream()
                .filter(event -> event.getType() == AgentStreamEventType.OBSERVATION)
                .count();
        assertTrue(observationEvents >= 2);
        assertTrue(events.stream()
                .filter(event -> event.getType() == AgentStreamEventType.OBSERVATION)
                .map(event -> String.valueOf(event.getData()))
                .anyMatch(data -> data.contains("重复")));
        org.mockito.Mockito.verify(actionExecutor, org.mockito.Mockito.times(1))
                .execute(any(), anyString(), anyString(), anyString(), any());
    }
}
