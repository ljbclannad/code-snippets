package com.health.agent.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.patient.AgentTaskConstraints;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.engine.subagent.CarePlanningSubAgent;
import com.health.agent.engine.subagent.DataCollectionSubAgent;
import com.health.agent.engine.subagent.SubAgentCoordinator;
import com.health.agent.engine.subagent.WeightAnalysisSubAgent;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentContext;
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

    @Test
    void shouldPreloadContextFromAgentExecutionCommand() {
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
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString())).thenReturn(
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
        assertTrue(context.getSlots().containsKey("patient_message"));
        assertTrue(context.getEvidenceRecords().stream()
                .anyMatch(record -> "PATIENT_MESSAGE".equals(record.getSource())
                        && "最近两周体重几乎不下降".equals(record.getSummary())));
    }

    @Test
    void shouldBuildPatientTaskSpecificFirstTurnPromptFromCommand() {
        AgentSession session = AgentSession.builder().id("1").build();
        when(sessionService.createSession(anyString(), anyString(), anyString())).thenReturn(session);
        when(toolRegistry.getAllTools()).thenReturn(List.of(
                Tool.builder()
                        .name("detect_weight_plateau")
                        .description("识别是否进入减重停滞期")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("weightRecords", Map.of("type", "array")))
                        .build()));

        AtomicReference<List<Message>> capturedMessages = new AtomicReference<>();
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
                .patientMessage("医生，我这周晚上基本没吃主食，但体重还是没怎么掉，是不是进入平台期了？")
                .sessionContext(Map.of(
                        "conversationPhase", "follow_up",
                        "currentTopic", "weight_plateau"))
                .profileSnapshot(Map.of(
                        "gender", "female",
                        "age", 32,
                        "currentWeightKg", 68.5))
                .memorySnapshot(MemorySnapshot.builder()
                        .shortTermMemories(List.of(
                                "近7天体重在68.4kg到68.7kg之间波动",
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
        assertTrue(firstPrompt.contains("Task Type: PATIENT_FOLLOW_UP_ANALYSIS"));
        assertTrue(firstPrompt.contains("Current Patient Message"));
        assertTrue(firstPrompt.contains("Trigger Decision Summary"));
        assertTrue(firstPrompt.contains("Profile Snapshot Summary"));
        assertTrue(firstPrompt.contains("Short-term Memory Snapshot"));
        assertTrue(firstPrompt.contains("Long-term Memory Snapshot"));
        assertTrue(firstPrompt.contains("Task-specific Analysis Focus"));
        assertTrue(firstPrompt.contains("平台期风险"));
        assertTrue(firstPrompt.contains("近7天体重在68.4kg到68.7kg之间波动"));
        assertTrue(firstPrompt.contains("female"));
        assertTrue(firstPrompt.contains("命中随访趋势分析信号"));
    }

    @Test
    void resumeStreamShouldUpdateContextAndEmitContextUpdatedEventAfterApprovedAction() {
        AgentContext persistedContext = AgentContext.initialize("C001", "D001", "恢复随访分析");
        AgentSession session = AgentSession.builder()
                .id("1")
                .customerId("C001")
                .doctorId("D001")
                .goal("恢复随访分析")
                .status(SessionStatus.PAUSED.name())
                .historyJson(JSON.toJSONString(List.of(Step.thought("等待审批"))))
                .messagesJson(JSON.toJSONString(List.of(Message.user("请继续分析"))))
                .contextJson(JSON.toJSONString(persistedContext))
                .currentStep(1)
                .build();
        when(sessionService.getById("1")).thenReturn(session);

        ActionDecision approvedAction = ActionDecision.builder()
                .toolName("get_weight_history")
                .input(Map.of("customerId", "C001", "days", 14))
                .reason("审批后继续获取体重历史")
                .build();
        PendingTask approvedTask = PendingTask.builder()
                .id(101L)
                .agentSessionId("1")
                .status(TaskStatus.APPROVED.name())
                .inputJson(JSON.toJSONString(approvedAction))
                .build();
        when(sessionService.getPendingTask(101L)).thenReturn(approvedTask);

        when(actionExecutor.executeApprovedAction(any())).thenReturn(ActionResult.success(
                ToolExecutionOutput.builder()
                        .summary("已获取最近14天体重历史")
                        .stateUpdates(Map.of("weight_history", List.of(
                                Map.of("date", "2026-06-01", "weight", 68.5)
                        )))
                        .build()));

        when(toolRegistry.getAllTools()).thenReturn(List.of(
                Tool.builder()
                        .name("get_weight_history")
                        .description("查询体重历史")
                        .riskLevel(ToolRiskLevel.LOW)
                        .inputSchema(Map.of("customerId", Map.of("type", "string")))
                        .build()));

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
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> sink = invocation.getArgument(2);
            sink.accept(finalResponse.getContent());
            return finalResponse;
        }).when(deepSeekClient).chatStream(anyString(), anyList(), any());

        List<AgentStreamEvent> events = new ArrayList<>();
        assertDoesNotThrow(() -> engine.resumeStream("1", 101L, events::add));

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
        when(actionExecutor.execute(any(), anyString(), anyString(), anyString())).thenReturn(
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
                .execute(any(), anyString(), anyString(), anyString());
    }
}
