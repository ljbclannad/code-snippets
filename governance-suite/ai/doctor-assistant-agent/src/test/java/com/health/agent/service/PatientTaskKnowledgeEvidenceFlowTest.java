package com.health.agent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.health.agent.client.DeepSeekClient;
import com.health.agent.client.KnowledgeBaseClient;
import com.health.agent.config.DoctorAgentPlatformProperties;
import com.health.agent.config.DoctorSafetyProperties;
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.kb.KbSearchResult;
import com.health.agent.dto.patient.AgentTaskConstraints;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.engine.ActionExecutor;
import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.engine.ReActPromptBuilder;
import com.health.agent.engine.ReActResponseParser;
import com.health.agent.engine.subagent.CarePlanningSubAgent;
import com.health.agent.engine.subagent.DataCollectionSubAgent;
import com.health.agent.engine.subagent.RiskTriageSubAgent;
import com.health.agent.engine.subagent.SubAgentCoordinator;
import com.health.agent.engine.subagent.WeightAnalysisSubAgent;
import com.health.agent.mapper.HealthKnowledgeMapper;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.metrics.ToolMetrics;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.StructuredEvidenceItem;
import com.health.agent.scene.doctor.tool.DoctorToolDefinitionRegistry;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.tool.ToolRegistry;
import com.health.agent.tool.ToolInputValidator;
import com.lx.governance.agentplatform.model.AgentDefinition;
import com.lx.governance.agentplatform.model.AgentStatus;
import com.lx.governance.agentplatform.model.AgentType;
import com.lx.governance.agentplatform.registry.InMemoryAgentRegistry;
import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformContextDomain;
import com.lx.governance.agentplatform.model.PlatformContextField;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.PlatformTraceContext;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PatientTaskKnowledgeEvidenceFlowTest {

    private DeepSeekClient deepSeekClient;
    private AgentSessionService sessionService;
    private AgentAuditService agentAuditService;
    private KnowledgeBaseClient knowledgeBaseClient;

    private PatientTaskAgentService patientTaskAgentService;

    @BeforeEach
    void setUp() throws Exception {
        deepSeekClient = Mockito.mock(DeepSeekClient.class);
        sessionService = Mockito.mock(AgentSessionService.class);
        agentAuditService = Mockito.mock(AgentAuditService.class);
        knowledgeBaseClient = Mockito.mock(KnowledgeBaseClient.class);

        when(sessionService.createSession(anyString(), anyString(), anyString()))
                .thenReturn(AgentSession.builder().id("1").build());

        HealthKnowledgeMapper healthKnowledgeMapper = Mockito.mock(HealthKnowledgeMapper.class);
        when(knowledgeBaseClient.isEnabled()).thenReturn(true);

        KbSearchResult kbResult = new KbSearchResult();
        kbResult.setTitle("高血压运动建议");
        kbResult.setContent("建议每周进行中等强度有氧运动，并持续监测血压变化。");
        kbResult.setTags("高血压,运动");
        kbResult.setSource("HEALTH_KNOWLEDGE");
        kbResult.setTrustLevel("HIGH");
        kbResult.setHitReason("hybrid_search");
        kbResult.setScore(0.97);
        kbResult.setApplicableScope("doctor_assistant");
        when(knowledgeBaseClient.searchMedicalKnowledge("高血压运动", 3, null, null, null, null))
                .thenReturn(List.of(kbResult));

        HealthKnowledgeService healthKnowledgeService = new HealthKnowledgeService(
                healthKnowledgeMapper,
                knowledgeBaseClient,
                new AgentMetrics(new SimpleMeterRegistry()));

        ToolRegistry toolRegistry = new ToolRegistry(new DoctorToolDefinitionRegistry());
        ReflectionTestUtils.setField(toolRegistry, "patientService", Mockito.mock(PatientService.class));
        ReflectionTestUtils.setField(toolRegistry, "followUpTaskService", Mockito.mock(FollowUpTaskService.class));
        ReflectionTestUtils.setField(toolRegistry, "healthKnowledgeService", healthKnowledgeService);
        ReflectionTestUtils.setField(toolRegistry, "weightPlateauService", new com.health.agent.service.WeightPlateauService());
        ReflectionTestUtils.setField(toolRegistry, "adherenceAnalysisService", new com.health.agent.service.AdherenceAnalysisService());
        toolRegistry.registerTools();

        ActionExecutor actionExecutor = new ActionExecutor();
        ReflectionTestUtils.setField(actionExecutor, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(actionExecutor, "pendingTaskService", Mockito.mock(PendingTaskService.class));
        ReflectionTestUtils.setField(actionExecutor, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(actionExecutor, "toolMetrics", new ToolMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(actionExecutor, "toolInputValidator", new ToolInputValidator());
        ReflectionTestUtils.setField(actionExecutor, "platformToolOutputAdapter", new PlatformToolOutputAdapter());
        ReflectionTestUtils.setField(actionExecutor, "toolTimeoutMillis", 3000L);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorCorePoolSize", 2);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorMaxPoolSize", 4);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorQueueCapacity", 8);
        ReflectionTestUtils.setField(actionExecutor, "toolExecutorKeepAliveMillis", 60000L);

        ReActAgentEngine engine = new ReActAgentEngine();
        ReflectionTestUtils.setField(engine, "deepSeekClient", deepSeekClient);
        ReflectionTestUtils.setField(engine, "promptBuilder", new ReActPromptBuilder());
        ReflectionTestUtils.setField(engine, "responseParser", new ReActResponseParser());
        ReflectionTestUtils.setField(engine, "actionExecutor", actionExecutor);
        ReflectionTestUtils.setField(engine, "toolRegistry", toolRegistry);
        ReflectionTestUtils.setField(engine, "sessionService", sessionService);
        ReflectionTestUtils.setField(engine, "workflowService", Mockito.mock(AgentSessionWorkflowService.class));
        ReflectionTestUtils.setField(engine, "contextMutationService", new AgentContextMutationService());
        ReflectionTestUtils.setField(engine, "agentMetrics", new AgentMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(engine, "medicalSafetyService", new MedicalSafetyService());
        ReflectionTestUtils.setField(engine, "agentAuditService", agentAuditService);
        ReflectionTestUtils.setField(engine, "contextPrivacySanitizer",
                new ContextPrivacySanitizer(new DoctorSafetyProperties()));
        ReflectionTestUtils.setField(engine, "subAgentCoordinator", newCoordinator());
        ReflectionTestUtils.setField(engine, "maxSteps", 4);
        ReflectionTestUtils.setField(engine, "useMock", false);
        ReflectionTestUtils.setField(engine, "protocolRepairAttempts", 1);

        patientTaskAgentService = new PatientTaskAgentService(
                new PatientTaskSafetyService(),
                null,
                null,
                null,
                engine,
                new PatientTaskAgentResultMapper(),
                new PatientReplyComposer(),
                null,
                null,
                new DoctorAgentPlatformProperties(),
                new AgentMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void shouldCarryKnowledgeBaseEvidenceIntoAgentContextAndPatientVisibleEvidence() throws Exception {
        AtomicInteger invocation = new AtomicInteger();
        AtomicReference<List<Message>> secondRoundMessages = new AtomicReference<>();

        DeepSeekClient.DeepSeekResponse first = new DeepSeekClient.DeepSeekResponse();
        first.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "先查询高血压运动知识，补充可靠依据。",
                  "finished": false,
                  "action": {
                    "toolName": "get_health_knowledge",
                    "input": {
                      "topic": "高血压运动"
                    },
                    "reason": "先获取可靠医学依据"
                  }
                }
                """);
        DeepSeekClient.DeepSeekResponse second = new DeepSeekClient.DeepSeekResponse();
        second.setContent("""
                {
                  "protocolVersion": "v1",
                  "thinking": "已经拿到高可信知识依据，可以给出保守随访建议。",
                  "finished": true,
                  "finalAnswer": {
                    "summary": "结合现有资料，建议继续规律运动并监测血压变化。",
                    "keyFindings": ["已检索到高可信健康知识", "当前未见急症信号"],
                    "riskLevel": "LOW",
                    "recommendedActions": ["继续记录近3天血压与运动情况"],
                    "needsAttention": false,
                    "uncertainties": ["缺少近期线下检查结果"],
                    "supportingEvidence": ["已检索到高血压运动知识", "会话中未报告明显急症不适"],
                    "evidenceSources": ["HEALTH_KNOWLEDGE", "AI_CHAT_SESSION_CONTEXT"],
                    "inferences": ["当前适合给出保守随访建议"],
                    "evidenceDetails": [
                      {
                        "title": "高血压运动建议",
                        "summary": "建议每周进行中等强度有氧运动，并持续监测血压变化。",
                        "snippet": "建议每周进行中等强度有氧运动，并持续监测血压变化。",
                        "source": "HEALTH_KNOWLEDGE",
                        "trustLevel": "HIGH",
                        "hitReason": "hybrid_search",
                        "score": 0.97
                      }
                    ]
                  }
                }
                """);

        Mockito.doAnswer(invocationOnMock -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocationOnMock.getArgument(2);
            int round = invocation.incrementAndGet();
            if (round == 2) {
                @SuppressWarnings("unchecked")
                List<Message> messages = invocationOnMock.getArgument(1);
                secondRoundMessages.set(messages);
            }
            DeepSeekClient.DeepSeekResponse response = round == 1 ? first : second;
            sink.accept(response.getContent());
            return response;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());

        DoctorAssistantTaskResponse response = patientTaskAgentService.analyze(baseRequest());

        assertEquals(DoctorAssistantTaskStatus.SUCCESS, response.getStatus());
        assertNotNull(response.getStructuredResult());
        assertFalse(response.getStructuredResult().getEvidenceDetails().isEmpty());
        assertTrue(response.getStructuredResult().getEvidenceDetails().stream()
                .map(StructuredEvidenceItem::getTitle)
                .anyMatch("高血压运动建议"::equals));
        assertFalse(response.getPatientVisibleEvidence().isEmpty());
        assertTrue(response.getPatientVisibleEvidence().stream()
                .anyMatch(item -> "高血压运动建议".equals(item.getTitle())
                        && "HIGH".equals(item.getTrustLevel())));
        assertTrue(response.getDoctorWorkbenchPayload().getPatientVisibleEvidence().stream()
                .anyMatch(item -> "高血压运动建议".equals(item.getTitle())));
        assertTrue(response.getPatientSafeReply().contains("继续记录近3天血压与运动情况"));

        verify(knowledgeBaseClient).searchMedicalKnowledge("高血压运动", 3, null, null, null, null);

        ArgumentCaptor<AgentContext> contextCaptor = ArgumentCaptor.forClass(AgentContext.class);
        verify(sessionService).completeSession(anyString(), anyList(), contextCaptor.capture());
        AgentContext completedContext = contextCaptor.getValue();
        assertNotNull(completedContext.getSlots().get("health_knowledge"));
        assertTrue(String.valueOf(completedContext.getSlots().get("health_knowledge").getValue())
                .contains("高血压运动建议"));
        assertTrue(completedContext.getEvidenceRecords().stream()
                .anyMatch(record -> "HEALTH_KNOWLEDGE".equals(record.getSource())));

        List<Message> roundTwo = secondRoundMessages.get();
        assertNotNull(roundTwo);
        String roundTwoPrompt = roundTwo.stream().map(Message::getContent).reduce("", String::concat);
        assertTrue(roundTwoPrompt.contains("高血压运动建议"));
        assertTrue(roundTwoPrompt.contains("HEALTH_KNOWLEDGE"));
    }

    private DoctorAssistantTaskRequest baseRequest() {
        return DoctorAssistantTaskRequest.builder()
                .taskType(TaskType.PATIENT_FOLLOW_UP_ANALYSIS)
                .goal("请结合知识库分析这位患者的高血压运动管理建议")
                .customerId("C001")
                .doctorId("D001")
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .patientMessage("我最近在坚持运动，想确认高血压情况下怎么安排比较稳妥")
                .conversationId("conv-kb-1")
                .correlationId("corr-kb-1")
                .traceContext(PlatformTraceContext.builder()
                        .traceId("trace-kb-1")
                        .conversationId("conv-kb-1")
                        .correlationId("corr-kb-1")
                        .build())
                .platformContext(PlatformContext.builder()
                        .contextId("ctx:conv-kb-1")
                        .conversationId("conv-kb-1")
                        .customerId("C001")
                        .doctorId("D001")
                        .field(PlatformContextField.builder()
                                .fieldKey("session_context")
                                .domain(PlatformContextDomain.CONVERSATION_CONTEXT)
                                .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                                .source("AI_CHAT_SESSION_CONTEXT")
                                .value(Map.of("conversationPhase", "follow_up", "currentTopic", "高血压运动"))
                                .build())
                        .build())
                .sessionContext(Map.of(
                        "conversationPhase", "follow_up",
                        "currentTopic", "高血压运动"))
                .profileSnapshot(Map.of(
                        "age", 52,
                        "gender", "female",
                        "chronicDisease", "高血压"))
                .memorySnapshot(MemorySnapshot.builder()
                        .shortTermMemories(List.of("患者最近主动增加快走频率"))
                        .longTermMemories(List.of("既往存在血压波动"))
                        .build())
                .triggerDecision(Map.of("summary", "命中高价值随访分析"))
                .constraints(AgentTaskConstraints.builder()
                        .patientVisibleResponseOnly(true)
                        .requireEvidence(true)
                        .requireAuditTrail(true)
                        .build())
                .build();
    }

    private SubAgentCoordinator newCoordinator() {
        InMemoryAgentRegistry agentRegistry = new InMemoryAgentRegistry();
        List<com.health.agent.engine.subagent.AgentSubAgent> subAgents = List.of(
                new DataCollectionSubAgent(),
                new WeightAnalysisSubAgent(),
                new CarePlanningSubAgent(),
                new RiskTriageSubAgent());
        subAgents.forEach(subAgent -> agentRegistry.register(AgentDefinition.builder()
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
                .build()));
        return new SubAgentCoordinator(agentRegistry, subAgents);
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
}
