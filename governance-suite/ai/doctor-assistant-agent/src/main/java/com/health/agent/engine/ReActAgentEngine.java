package com.health.agent.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.client.DeepSeekClient;
import com.health.agent.config.DoctorAgentRuntimeProperties;
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.engine.subagent.AgentSubAgent;
import com.health.agent.engine.subagent.SubAgentCoordinator;
import com.health.agent.exception.AgentException;
import com.health.agent.learning.enums.AgentOutcomeLabel;
import com.health.agent.learning.model.AgentRunOutcome;
import com.health.agent.learning.model.AgentRunStrategySnapshot;
import com.health.agent.learning.service.AgentOutcomeAttributionService;
import com.health.agent.learning.service.AgentOutcomeRecorder;
import com.health.agent.learning.service.AgentRunStrategySnapshotRecorder;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.AgentStreamEvent;
import com.health.agent.model.AgentStreamEventType;
import com.health.agent.model.ContextEntry;
import com.health.agent.model.EvidenceRecord;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.ResultStatus;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.Step;
import com.health.agent.model.StructuredFinalAnswer;
import com.health.agent.model.TaskStatus;
import com.health.agent.model.ThoughtResult;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.safety.SafetyAssessment;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.AgentApprovalResumeState;
import com.health.agent.service.AgentContextMutationService;
import com.health.agent.service.AgentSessionService;
import com.health.agent.service.AgentSessionWorkflowService;
import com.health.agent.service.ContextPrivacySanitizer;
import com.lx.governance.agentplatform.model.AgentSelectionDecision;
import com.lx.governance.agentplatform.model.AgentSelectionMode;
import com.lx.governance.agentplatform.model.LoopContext;
import com.lx.governance.agentplatform.model.LoopOutcome;
import com.lx.governance.agentplatform.model.LoopTerminationReason;
import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.PlatformAgentHandoff;
import com.lx.governance.agentplatform.model.PlatformAgentHandoffItem;
import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformContextField;
import com.lx.governance.agentplatform.model.RunStatus;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.PlatformHandoffItemType;
import com.lx.governance.agentplatform.model.PromptTemplateType;
import com.lx.governance.agentplatform.runtime.AbstractPlatformLoopExecutor;
import com.lx.governance.agentplatform.runtime.LoopStepDecision;
import com.lx.governance.agentplatform.runtime.PlatformLoopExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * ReAct Agent 核心引擎
 */
@Slf4j
@Service
public class ReActAgentEngine {

    private static final String SLOT_SUB_AGENT_SELECTION_DECISION = "sub_agent_selection_decision";
    private static final String SLOT_SUB_AGENT_PENDING_PLAN = "sub_agent_pending_plan";
    private static final String SLOT_SUB_AGENT_EXECUTED_PLAN = "sub_agent_executed_plan";
    private static final String SELECTOR_VERSION = "sub-agent-coordinator-v1";
    private static final String TOOL_CATALOG_VERSION = "doctor-tool-catalog-v1";

    private final PlatformLoopExecutor<ReActLoopState, AgentResult> loopExecutor = new ReActPlatformLoopExecutor();

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private AgentPromptProvider promptBuilder;

    @Autowired
    private ReActResponseParser responseParser;

    @Autowired
    private ActionExecutor actionExecutor;

    @Autowired
    private AgentToolCatalog toolRegistry;

    @Autowired
    private AgentSessionService sessionService;

    @Autowired
    private AgentSessionWorkflowService workflowService;

    @Autowired
    private AgentContextMutationService contextMutationService;

    @Autowired
    private AgentMetrics agentMetrics;

    @Autowired
    private MedicalSafetyService medicalSafetyService;

    @Autowired
    private AgentAuditService agentAuditService;

    @Autowired
    private SubAgentCoordinator subAgentCoordinator;

    @Autowired(required = false)
    private ContextPrivacySanitizer contextPrivacySanitizer;

    @Autowired(required = false)
    private DoctorAgentRuntimeProperties runtimeProperties;

    @Autowired(required = false)
    private AgentRunStrategySnapshotRecorder strategySnapshotRecorder;

    @Autowired(required = false)
    private AgentOutcomeRecorder outcomeRecorder;

    @Autowired(required = false)
    private AgentOutcomeAttributionService attributionService;

    private int maxSteps = 15;

    private boolean useMock = false;

    private int protocolRepairAttempts = 1;

    /**
     * 执行 Agent 任务
     */
    public AgentResult execute(String goal, String customerId, String doctorId) {
        long startTime = System.currentTimeMillis();
        log.info("Agent开始执行任务: goal={}, customerId={}", goal, customerId);

        // 记录执行开始
        agentMetrics.recordExecutionStart();

        // 创建会话
        AgentSession session = sessionService.createSession(customerId, doctorId, goal);
        String sessionId = String.valueOf(session.getId());

        List<Step> history = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        AgentContext context = AgentContext.initialize(customerId, doctorId, goal);

        // 通过关键词处理边界与安全
        SafetyAssessment goalAssessment = medicalSafetyService.assessGoal(goal);
        recordInitialStrategySnapshot(sessionId, context, null);
        if (goalAssessment.isBlocked()) {
            context.addEvidence("goal_assessment", "SAFETY_RULE", goalAssessment.message(), Map.of("goal", goal));
            recordSafetyBlock(sessionId, customerId, doctorId, goal, goalAssessment, Map.of("goal", goal));
            sessionService.completeSession(sessionId, history, context);
            AgentResult result = AgentResult.success(
                    sessionId,
                    medicalSafetyService.buildSafetyStructuredAnswer(goalAssessment.message()),
                    history,
                    0);
            recordOutcome(sessionId, context, result, System.currentTimeMillis() - startTime,
                    AgentOutcomeLabel.FAILED_SAFETY_BLOCK, null, goalAssessment.message());
            agentAuditService.recordSessionResult(sessionId, customerId, doctorId, goal, result, 0,
                    System.currentTimeMillis() - startTime);
            return result;
        }

        messages.add(Message.user(promptBuilder.buildUserPrompt(goal, customerId)));
        return executeLoop(newLoopContext(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                0,
                startTime,
                null,
                context)).getResult();
    }

    public AgentResult executeStream(
            String goal,
            String customerId,
            String doctorId,
            Consumer<AgentStreamEvent> eventSink) {
        AgentExecutionCommand command = AgentExecutionCommand.builder()
                .goal(goal)
                .customerId(customerId)
                .doctorId(doctorId)
                .build();
        return executeStream(command, eventSink);
    }

    public AgentResult executeStream(
            AgentExecutionCommand command,
            Consumer<AgentStreamEvent> eventSink) {
        long startTime = System.currentTimeMillis();
        String goal = command != null ? command.getGoal() : null;
        String customerId = command != null ? command.getCustomerId() : null;
        String doctorId = command != null ? command.getDoctorId() : null;
        log.info("Agent开始流式执行任务: goal={}, customerId={}", goal, customerId);

        agentMetrics.recordExecutionStart();

        AgentSession session = command != null && command.getConversationId() != null
                ? sessionService.createSession(customerId, doctorId, goal, command.getConversationId())
                : sessionService.createSession(customerId, doctorId, goal);
        String sessionId = String.valueOf(session.getId());

        List<Step> history = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        AgentContext context = createInitialContext(command);
        recordInitialStrategySnapshot(sessionId, context, command);

        // 先做目标级安全预检，命中高危规则时直接返回安全结果，不进入模型循环。
        SafetyAssessment goalAssessment = medicalSafetyService.assessGoal(goal);
        if (goalAssessment.isBlocked()) {
            context.addEvidence("goal_assessment", "SAFETY_RULE", goalAssessment.message(), Map.of("goal", goal));
            recordSafetyBlock(sessionId, customerId, doctorId, goal, goalAssessment, Map.of("goal", goal));
            sessionService.completeSession(sessionId, history, context);
            AgentResult result = AgentResult.success(
                    sessionId,
                    medicalSafetyService.buildSafetyStructuredAnswer(goalAssessment.message()),
                    history,
                    0);
            recordOutcome(sessionId, context, result, System.currentTimeMillis() - startTime,
                    AgentOutcomeLabel.FAILED_SAFETY_BLOCK, null, goalAssessment.message());
            emitEvent(eventSink, sessionId, 0, AgentStreamEventType.FINAL_ANSWER, result.getStructuredFinalAnswer());
            emitDone(eventSink, sessionId, 0, result.getStatus().name());
            agentAuditService.recordSessionResult(sessionId, customerId, doctorId, goal, result, 0,
                    System.currentTimeMillis() - startTime);
            return result;
        }

        // 流式任务启动时先登记选择计划，再由循环消费已计划的角色。
        selectSubAgent(sessionId, goal, context);
        messages.add(Message.user(promptBuilder.buildUserPrompt(command)));
        return executeLoop(newLoopContext(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                0,
                startTime,
                eventSink,
                context)).getResult();
    }

    private AgentContext createInitialContext(AgentExecutionCommand command) {
        String goal = command != null ? command.getGoal() : null;
        String customerId = command != null ? command.getCustomerId() : null;
        String doctorId = command != null ? command.getDoctorId() : null;
        AgentContext context = AgentContext.initialize(customerId, doctorId, goal);
        preloadContext(context, command);
        return context;
    }

    @Autowired(required = false)
    void applyRuntimeProperties(DoctorAgentRuntimeProperties runtimeProperties) {
        if (runtimeProperties == null) {
            return;
        }
        this.runtimeProperties = runtimeProperties;
        this.maxSteps = runtimeProperties.getMaxSteps();
        this.useMock = runtimeProperties.isUseMock();
        this.protocolRepairAttempts = runtimeProperties.getProtocolRepairAttempts();
    }

    private void preloadContext(AgentContext context, AgentExecutionCommand command) {
        if (context == null || command == null) {
            return;
        }
        if (command.getPlatformContext() != null) {
            preloadPlatformContext(context, command.getPlatformContext());
        }

        Map<String, Object> patientTaskRequest = new java.util.LinkedHashMap<>();
        putIfNotNull(patientTaskRequest, "taskType",
                command.getTaskType() != null ? command.getTaskType().name() : null);
        putIfNotNull(patientTaskRequest, "source",
                command.getSource() != null ? command.getSource().name() : null);
        putIfNotNull(patientTaskRequest, "conversationId", command.getConversationId());
        putIfNotNull(patientTaskRequest, "correlationId", command.getCorrelationId());
        if (!patientTaskRequest.isEmpty()) {
            context.putSlot("patient_task_request", patientTaskRequest, "REQUEST_PRELOAD",
                    PlatformContextVisibility.AGENT_INTERNAL);
        }

        if (command.getSessionContext() != null && !command.getSessionContext().isEmpty()) {
            context.putSlot("session_context", command.getSessionContext(), "REQUEST_PRELOAD",
                    PlatformContextVisibility.AGENT_INTERNAL);
            context.addEvidence("session_context", "AI_CHAT_SESSION_CONTEXT",
                    JSON.toJSONString(command.getSessionContext()), command.getSessionContext(),
                    PlatformContextVisibility.AGENT_INTERNAL);
        }
        if (command.getProfileSnapshot() != null && !command.getProfileSnapshot().isEmpty()) {
            context.putSlot("profile_snapshot", command.getProfileSnapshot(), "REQUEST_PRELOAD",
                    PlatformContextVisibility.AGENT_INTERNAL);
            context.putSlot("patient_profile", command.getProfileSnapshot(), "REQUEST_PRELOAD_NORMALIZED",
                    PlatformContextVisibility.AGENT_INTERNAL);
            context.addEvidence("profile_snapshot", "PROFILE_SNAPSHOT",
                    JSON.toJSONString(command.getProfileSnapshot()), command.getProfileSnapshot(),
                    PlatformContextVisibility.AGENT_INTERNAL);
        }
        if (command.getMemorySnapshot() != null) {
            context.putSlot("memory_snapshot", command.getMemorySnapshot(), "REQUEST_PRELOAD",
                    PlatformContextVisibility.AGENT_INTERNAL);
            context.addEvidence("memory_snapshot", "AI_CHAT_MEMORY",
                    JSON.toJSONString(command.getMemorySnapshot()), command.getMemorySnapshot(),
                    PlatformContextVisibility.AGENT_INTERNAL);
            if (command.getMemorySnapshot().getShortTermMemories() != null
                    && !command.getMemorySnapshot().getShortTermMemories().isEmpty()) {
                context.putSlot("chat_history", command.getMemorySnapshot().getShortTermMemories(),
                        "REQUEST_PRELOAD_NORMALIZED", PlatformContextVisibility.AGENT_INTERNAL);
                context.putSlot("recent_follow_up_signals", command.getMemorySnapshot().getShortTermMemories(),
                        "REQUEST_PRELOAD_NORMALIZED", PlatformContextVisibility.AGENT_INTERNAL);
            }
        }
        if (command.getTriggerDecision() != null && !command.getTriggerDecision().isEmpty()) {
            context.putSlot("trigger_decision", command.getTriggerDecision(), "REQUEST_PRELOAD",
                    PlatformContextVisibility.AUDIT_ONLY);
            context.addEvidence("trigger_decision", "TRIGGER_DECISION",
                    JSON.toJSONString(command.getTriggerDecision()), command.getTriggerDecision(),
                    PlatformContextVisibility.AUDIT_ONLY);
        }
        if (command.getPatientMessage() != null && !command.getPatientMessage().isBlank()) {
            context.putSlot("patient_message", Map.of(
                    "content", command.getPatientMessage(),
                    "conversationId", defaultString(command.getConversationId()),
                    "correlationId", defaultString(command.getCorrelationId())), "REQUEST_PRELOAD",
                    PlatformContextVisibility.AGENT_INTERNAL);
            context.addEvidence(
                    "patient_message",
                    "PATIENT_MESSAGE",
                    command.getPatientMessage(),
                    Map.of(
                            "conversationId", defaultString(command.getConversationId()),
                            "correlationId", defaultString(command.getCorrelationId()),
                            "taskType", command.getTaskType() != null ? command.getTaskType().name() : "UNKNOWN"),
                    PlatformContextVisibility.AGENT_INTERNAL);
        }
        if (command.getPatientMessage() != null && !command.getPatientMessage().isBlank()
                && !context.getSlots().containsKey("recent_follow_up_signals")) {
            context.putSlot("recent_follow_up_signals", List.of(command.getPatientMessage()),
                    "REQUEST_PRELOAD_NORMALIZED", PlatformContextVisibility.AGENT_INTERNAL);
        }
    }

    private void preloadPlatformContext(AgentContext context, PlatformContext platformContext) {
        if (context == null || platformContext == null || platformContext.getFields() == null) {
            return;
        }
        for (PlatformContextField field : platformContext.getFields()) {
            if (field == null || field.getFieldKey() == null || field.getValue() == null) {
                continue;
            }
            Object normalized = normalizePlatformContextValue(field);
            PlatformContextVisibility visibility = field.getVisibility() != null
                    ? field.getVisibility()
                    : PlatformContextVisibility.AGENT_INTERNAL;
            context.putSlot(field.getFieldKey(), normalized, defaultString(field.getSource()), visibility);
            context.addEvidence(
                    field.getFieldKey(),
                    defaultString(field.getSource()),
                    "PlatformContext preload: " + field.getFieldKey(),
                    normalized,
                    visibility);
        }
    }

    @SuppressWarnings("unchecked")
    private Object normalizePlatformContextValue(PlatformContextField field) {
        if (field == null || field.getValue() == null) {
            return null;
        }
        Object value = field.getValue();
        return switch (field.getFieldKey()) {
            case "session_context", "profile_snapshot", "trigger_decision" ->
                    value instanceof Map<?, ?> map ? (Map<String, Object>) map : JSON.parseObject(JSON.toJSONString(value));
            case "memory_snapshot" -> value instanceof com.health.agent.dto.patient.MemorySnapshot
                    ? value
                    : JSON.parseObject(JSON.toJSONString(value), com.health.agent.dto.patient.MemorySnapshot.class);
            case "patient_message" -> value instanceof String string
                    ? Map.of("content", string)
                    : value;
            default -> value;
        };
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || key.isBlank() || value == null) {
            return;
        }
        target.put(key, value);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    /**
     * 继续执行（用于人工确认后恢复）
     */
    public AgentResult resume(String sessionId, Long approvedTaskId) {
        long startTime = System.currentTimeMillis();
        log.info("Agent恢复执行: sessionId={}, taskId={}", sessionId, approvedTaskId);

        try {
            AgentApprovalResumeState resumeState = loadResumeState(sessionId, approvedTaskId);
            AgentSession session = resumeState.getSession();
            List<Step> history = resumeState.getHistory();
            List<Message> messages = resumeState.getMessages();
            AgentContext context = resumeState.getContext();
            int currentStep = resumeState.getCurrentStep();

            log.info("恢复会话状态: sessionId={}, currentStep={}, historySize={}",
                    sessionId, currentStep, history.size());

            // 5. 执行已批准的行动
            ActionDecision action = resumeState.getApprovedAction();
            log.info("执行已批准的行动: tool={}, input={}",
                    action.getToolName(), JSON.toJSONString(action.getInput()));

            Map<String, Object> beforeSlotSnapshot = snapshotContextSlots(context);
            ActionResult actionResult = actionExecutor.executeApprovedAction(action);

            // 6. 记录 Observation
            String observation = contextMutationService.formatObservation(actionResult);
            history.add(Step.observation(observation));
            messages.add(Message.user(ReActPromptHistory.OBSERVATION_PREFIX + observation));
            contextMutationService.applyActionResult(context, action, actionResult, observation);
            recordResumeContextUpdate(session, context, action, beforeSlotSnapshot);

            log.info("行动执行完成: {}", observation);

            // 7. 恢复会话为运行状态
            sessionService.resumeSession(sessionId);

            // 8. 继续 ReAct 循环
            return continueReActLoop(
                    sessionId,
                    session.getGoal(),
                    session.getCustomerId(),
                    session.getDoctorId(),
                    history,
                    messages,
                    currentStep + 1,
                    context);

        } catch (Exception e) {
            log.error("Agent恢复执行异常", e);
            sessionService.failSession(sessionId, e.getMessage());
            StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                    "恢复执行失败，当前会话无法继续自动推进。",
                    "HIGH",
                    "恢复执行失败",
                    List.of("请人工复核当前待审批任务和会话状态", "必要时重新发起新的减重分析会话"),
                    List.of("自动恢复链路已中断", e.getMessage()));
            AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, new ArrayList<>(), 0);
            recordOutcome(sessionId, null, result, System.currentTimeMillis() - startTime,
                    AgentOutcomeLabel.FAILED_RUNTIME, e.getClass().getSimpleName(), e.getMessage());
            return result;
        }
    }

    public AgentResult resumeStream(String sessionId, Long approvedTaskId, Consumer<AgentStreamEvent> eventSink) {
        long startTime = System.currentTimeMillis();
        log.info("Agent流式恢复执行: sessionId={}, taskId={}", sessionId, approvedTaskId);

        try {
            AgentApprovalResumeState resumeState = loadResumeState(sessionId, approvedTaskId);
            AgentSession session = resumeState.getSession();
            List<Step> history = resumeState.getHistory();
            List<Message> messages = resumeState.getMessages();
            AgentContext context = resumeState.getContext();
            int currentStep = resumeState.getCurrentStep();
            ActionDecision action = resumeState.getApprovedAction();
            Map<String, Object> beforeSlotSnapshot = snapshotContextSlots(context);
            ActionResult actionResult = actionExecutor.executeApprovedAction(action);

            String observation = contextMutationService.formatObservation(actionResult);
            history.add(Step.observation(observation));
            messages.add(Message.user(ReActPromptHistory.OBSERVATION_PREFIX + observation));
            emitEvent(eventSink, sessionId, currentStep + 1, AgentStreamEventType.OBSERVATION, observation);
            contextMutationService.applyActionResult(context, action, actionResult, observation);
            emitContextUpdated(sessionId, currentStep + 1, context, action.getToolName(), eventSink);
            recordResumeContextUpdate(session, context, action, beforeSlotSnapshot);

            sessionService.resumeSession(sessionId);

            return continueReActLoopStream(
                    sessionId,
                    session.getGoal(),
                    session.getCustomerId(),
                    session.getDoctorId(),
                    history,
                    messages,
                    currentStep + 1,
                    startTime,
                    eventSink,
                    context);
        } catch (Exception e) {
            log.error("Agent恢复执行异常", e);
            sessionService.failSession(sessionId, e.getMessage());
            StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                    "恢复执行失败，当前会话无法继续自动推进。",
                    "HIGH",
                    "恢复执行失败",
                    List.of("请人工复核当前待审批任务和会话状态", "必要时重新发起新的减重分析会话"),
                    List.of("自动恢复链路已中断", e.getMessage()));
            AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, new ArrayList<>(), 0);
            recordOutcome(sessionId, null, result, System.currentTimeMillis() - startTime,
                    AgentOutcomeLabel.FAILED_RUNTIME, e.getClass().getSimpleName(), e.getMessage());
            emitEvent(eventSink, sessionId, 0, AgentStreamEventType.ERROR,
                    Map.of("message", e.getMessage(), "structuredFinalAnswer", degraded));
            emitDone(eventSink, sessionId, 0, result.getStatus().name());
            return result;
        }
    }

    private AgentApprovalResumeState loadResumeState(String sessionId, Long approvedTaskId) {
        return workflowService.loadApprovedResumeState(sessionId, approvedTaskId);
    }

    /**
     * 继续 ReAct 循环
     */
    private AgentResult continueReActLoop(
            String sessionId,
            String goal,
            String customerId,
            String doctorId,
            List<Step> history,
            List<Message> messages,
            int startStep,
            AgentContext context) {
        log.info("继续ReAct循环: sessionId={}, startStep={}", sessionId, startStep);
        return executeLoop(newLoopContext(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                startStep,
                System.currentTimeMillis(),
                null,
                context)).getResult();
    }

    private AgentResult continueReActLoopStream(
            String sessionId,
            String goal,
            String customerId,
            String doctorId,
            List<Step> history,
            List<Message> messages,
            int startStep,
            long startTime,
            Consumer<AgentStreamEvent> eventSink,
            AgentContext context) {
        log.info("继续流式ReAct循环: sessionId={}, startStep={}", sessionId, startStep);
        return executeLoop(newLoopContext(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                startStep,
                startTime,
                eventSink,
                context)).getResult();
    }

    private LoopContext<ReActLoopState> newLoopContext(
            String sessionId,
            String goal,
            String customerId,
            String doctorId,
            List<Step> history,
            List<Message> messages,
            int startStep,
            long startTime,
            Consumer<AgentStreamEvent> eventSink,
            AgentContext context) {
        return LoopContext.<ReActLoopState>builder()
                .runId(sessionId)
                .workflowId("doctor-react-loop")
                .goal(goal)
                .startStep(startStep)
                .maxSteps(maxSteps)
                .startedAt(startTime)
                .state(ReActLoopState.builder()
                        .customerId(customerId)
                        .doctorId(doctorId)
                        .history(history)
                        .messages(messages)
                        .eventSink(eventSink)
                        .context(context)
                        .build())
                .build();
    }

    private LoopOutcome<ReActLoopState, AgentResult> executeLoop(LoopContext<ReActLoopState> execution) {
        return loopExecutor.executeLoop(execution);
    }

    private ParsedThought requestThought(
            LoopContext<ReActLoopState> execution,
            String systemPrompt,
            int step,
            int stepNumber,
            AgentContext promptContext) {
        List<Message> messages = ReActPromptHistory.forModel(loopState(execution).getMessages());
        if (loopState(execution).getEventSink() == null) {
            return requestThoughtResult(systemPrompt, messages, step,
                    loopState(execution).getCustomerId(), promptContext);
        }
        return requestThoughtResultStream(systemPrompt, messages, step,
                loopState(execution).getCustomerId(), execution.getRunId(), loopState(execution).getEventSink(),
                stepNumber, promptContext);
    }

    private LoopOutcome<ReActLoopState, AgentResult> finalizeSuccess(
            LoopContext<ReActLoopState> execution,
            int stepNumber,
            StructuredFinalAnswer finalAnswer) {
        SafetyAssessment finalAnswerAssessment = medicalSafetyService.assessFinalAnswer(finalAnswer,
                loopState(execution).getHistory());
        sessionService.completeSession(execution.getRunId(), loopState(execution).getHistory(),
                loopState(execution).getContext());
        long duration = System.currentTimeMillis() - execution.getStartedAt();
        agentMetrics.recordExecutionSuccess(stepNumber, duration);

        AgentResult result = AgentResult.success(
                execution.getRunId(),
                finalAnswerAssessment.isBlocked()
                        ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                        : finalAnswer,
                loopState(execution).getHistory(),
                stepNumber);
        if (finalAnswerAssessment.isBlocked()) {
            recordSafetyBlock(execution.getRunId(), loopState(execution).getCustomerId(),
                    loopState(execution).getDoctorId(), execution.getGoal(), finalAnswerAssessment, finalAnswer);
        }
        emitEvent(loopState(execution).getEventSink(), execution.getRunId(), stepNumber,
                AgentStreamEventType.FINAL_ANSWER,
                result.getStructuredFinalAnswer());
        emitSubAgentFinished(execution.getRunId(), stepNumber, loopState(execution).getContext(),
                loopState(execution).getEventSink());
        emitDone(loopState(execution).getEventSink(), execution.getRunId(), stepNumber, result.getStatus().name());
        agentAuditService.recordSessionResult(execution.getRunId(), loopState(execution).getCustomerId(),
                loopState(execution).getDoctorId(), execution.getGoal(), result, stepNumber, duration);
        recordOutcome(execution.getRunId(), loopState(execution).getContext(), result, duration,
                finalAnswerAssessment.isBlocked() ? AgentOutcomeLabel.FAILED_SAFETY_BLOCK : AgentOutcomeLabel.SUCCESS,
                null, finalAnswerAssessment.isBlocked() ? finalAnswerAssessment.message() : null);
        return buildLoopOutcome(execution, LoopTerminationReason.FINISHED, RunStatus.COMPLETED, stepNumber, result,
                null);
    }

    private LoopOutcome<ReActLoopState, AgentResult> finalizePendingApproval(
            LoopContext<ReActLoopState> execution,
            int stepNumber,
            ActionDecision action,
            ActionResult actionResult) {
        log.info("行动需要医生确认，Agent暂停");
        sessionService.pauseSession(execution.getRunId(), loopState(execution).getHistory(),
                loopState(execution).getMessages(), loopState(execution).getContext());
        agentMetrics.recordPendingApproval(action.getToolName());

        AgentResult result = AgentResult.pendingApproval(
                execution.getRunId(),
                actionResult.getTask(),
                loopState(execution).getHistory(),
                stepNumber);
        emitEvent(loopState(execution).getEventSink(), execution.getRunId(), stepNumber,
                AgentStreamEventType.PENDING_APPROVAL, actionResult.getTask());
        emitSubAgentFinished(execution.getRunId(), stepNumber, loopState(execution).getContext(),
                loopState(execution).getEventSink());
        emitDone(loopState(execution).getEventSink(), execution.getRunId(), stepNumber, result.getStatus().name());
        agentAuditService.recordSessionResult(execution.getRunId(), loopState(execution).getCustomerId(),
                loopState(execution).getDoctorId(), execution.getGoal(), result, stepNumber,
                System.currentTimeMillis() - execution.getStartedAt());
        recordOutcome(execution.getRunId(), loopState(execution).getContext(), result,
                System.currentTimeMillis() - execution.getStartedAt(),
                AgentOutcomeLabel.MANUAL_REVIEW_REQUIRED, null, null);
        return buildLoopOutcome(execution, LoopTerminationReason.WAITING_APPROVAL, RunStatus.WAITING_APPROVAL,
                stepNumber, result, null);
    }

    private LoopOutcome<ReActLoopState, AgentResult> finalizeError(
            LoopContext<ReActLoopState> execution,
            int stepNumber,
            Exception e) {
        log.error("Agent执行异常", e);
        sessionService.failSession(execution.getRunId(), e.getMessage());
        long duration = System.currentTimeMillis() - execution.getStartedAt();
        agentMetrics.recordExecutionFailure(e.getClass().getSimpleName(), stepNumber, duration);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                buildExecutionDegradedSummary(e),
                "MEDIUM",
                buildExecutionRuntimeEvidence(e),
                List.of("请人工接管当前会话", "补充近期关键数据后再尝试重新分析"),
                List.of("当前未获得可用的自动分析结果"));
        AgentResult result = AgentResult.error(execution.getRunId(), e.getMessage(), degraded,
                loopState(execution).getHistory(),
                stepNumber);
        emitEvent(loopState(execution).getEventSink(), execution.getRunId(), stepNumber, AgentStreamEventType.ERROR,
                errorEventData(e, degraded));
        emitSubAgentFinished(execution.getRunId(), stepNumber, loopState(execution).getContext(),
                loopState(execution).getEventSink());
        emitDone(loopState(execution).getEventSink(), execution.getRunId(), stepNumber, result.getStatus().name());
        agentAuditService.recordSessionResult(execution.getRunId(), loopState(execution).getCustomerId(),
                loopState(execution).getDoctorId(), execution.getGoal(), result, stepNumber, duration);
        recordOutcome(execution.getRunId(), loopState(execution).getContext(), result, duration,
                classifyFailureOutcomeLabel(e), e.getClass().getSimpleName(), e.getMessage());
        return buildLoopOutcome(execution, LoopTerminationReason.FAILED, RunStatus.FAILED, stepNumber, result,
                e.getMessage());
    }

    private LoopOutcome<ReActLoopState, AgentResult> finalizeMaxSteps(LoopContext<ReActLoopState> execution) {
        log.warn("Agent达到最大步数限制");
        sessionService.failSession(execution.getRunId(), "达到最大步数限制");
        long duration = System.currentTimeMillis() - execution.getStartedAt();
        agentMetrics.recordMaxStepsReached(maxSteps, duration);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                "自动分析已达到最大步数，未能收敛到最终结论。",
                "MEDIUM",
                "达到最大步数限制",
                List.of("请人工查看已有 Observation", "必要时缩小问题范围后重新发起分析"),
                List.of("当前分析链路未完成"));
        AgentResult result = AgentResult.maxStepsReached(execution.getRunId(), degraded,
                loopState(execution).getHistory(), execution.getMaxSteps());
        emitEvent(loopState(execution).getEventSink(), execution.getRunId(), execution.getMaxSteps(),
                AgentStreamEventType.FINAL_ANSWER, degraded);
        emitSubAgentFinished(execution.getRunId(), execution.getMaxSteps(), loopState(execution).getContext(),
                loopState(execution).getEventSink());
        emitDone(loopState(execution).getEventSink(), execution.getRunId(), execution.getMaxSteps(),
                result.getStatus().name());
        agentAuditService.recordSessionResult(execution.getRunId(), loopState(execution).getCustomerId(),
                loopState(execution).getDoctorId(), execution.getGoal(), result, execution.getMaxSteps(), duration);
        recordOutcome(execution.getRunId(), loopState(execution).getContext(), result, duration,
                AgentOutcomeLabel.FAILED_RUNTIME, "MAX_STEPS", "达到最大步数限制");
        return buildLoopOutcome(execution, LoopTerminationReason.MAX_STEPS, RunStatus.DEGRADED,
                execution.getMaxSteps(), result, "达到最大步数限制");
    }

    private void applySyntheticObservation(
            LoopContext<ReActLoopState> execution,
            int stepNumber,
            ActionDecision action,
            String observation,
            String evidenceSource) {
        loopState(execution).getHistory().add(Step.observation(observation));
        loopState(execution).getMessages().add(Message.user(ReActPromptHistory.OBSERVATION_PREFIX + observation));
        loopState(execution).getContext().addEvidence(action.getToolName(), evidenceSource, observation, action.getInput());
        updateLastToolResult(loopState(execution).getContext(), action, observation);
        emitContextUpdated(execution.getRunId(), stepNumber, loopState(execution).getContext(), action.getToolName(),
                loopState(execution).getEventSink());
        emitEvent(loopState(execution).getEventSink(), execution.getRunId(), stepNumber,
                AgentStreamEventType.OBSERVATION,
                observation);
    }

    private boolean isRepeatedAction(AgentContext context, List<Step> history, ActionDecision action) {
        if (action == null || context == null) {
            return false;
        }
        ContextEntry lastToolEntry = context.getSlots().get("last_tool_result");
        if (!(lastToolEntry != null && lastToolEntry.getValue() instanceof Map<?, ?> lastToolResult)) {
            return false;
        }
        Object lastToolName = lastToolResult.get("toolName");
        Object lastInput = lastToolResult.get("input");
        if (!String.valueOf(lastToolName).equals(action.getToolName())) {
            return false;
        }
        if (!JSON.toJSONString(lastInput).equals(JSON.toJSONString(action.getInput()))) {
            return false;
        }
        long recentSameActions = history.stream()
                .filter(step -> step.getType() == com.health.agent.model.StepType.ACTION)
                .map(Step::getAction)
                .filter(java.util.Objects::nonNull)
                .filter(previous -> action.getToolName().equals(previous.getToolName()))
                .limit(2)
                .count();
        return recentSameActions >= 1;
    }

    private String buildDuplicateActionObservation(ActionDecision action) {
        return "检测到重复工具调用：该工具刚刚已用相同输入执行且未引入新信息，请改用其他工具补充证据，或基于现有证据直接收敛。";
    }

    private class ReActPlatformLoopExecutor extends AbstractPlatformLoopExecutor<ReActLoopState, AgentResult> {

        @Override
        protected void beforeStep(LoopContext<ReActLoopState> loopContext, int step, int stepNumber) {
            log.info("Agent执行第{}步", stepNumber);
        }

        @Override
        protected LoopStepDecision<ReActLoopState, AgentResult> executeStep(
                LoopContext<ReActLoopState> execution,
                int step,
                int stepNumber) {
            AgentSubAgent subAgent = selectSubAgent(execution.getRunId(), execution.getGoal(), loopState(execution).getContext());
            switchSubAgent(execution.getRunId(), stepNumber, loopState(execution).getContext(), subAgent,
                    loopState(execution).getEventSink());
            AgentContext visibleContext = projectContextForSubAgent(loopState(execution).getContext(), subAgent);
            AgentAccessContext accessContext = buildToolAccessContext(loopState(execution).getContext());
            RenderedPrompt renderedSystemPrompt = promptBuilder.renderSubAgentSystemPromptFromCatalog(
                    subAgent,
                    toolRegistry.listToolDescriptors(accessContext));
            recordRenderedPromptSnapshot(execution.getRunId(), visibleContext, renderedSystemPrompt);
            ParsedThought parsedThought = requestThought(execution, renderedSystemPrompt.getContent(), step, stepNumber, visibleContext);
            log.info("Agent响应: {}", parsedThought.responseText());

            ThoughtResult thought = parsedThought.thoughtResult();
            loopState(execution).getHistory().add(Step.thought(thought.getThinking()));
            loopState(execution).getMessages().add(Message.assistant(parsedThought.responseText()));
            emitEvent(loopState(execution).getEventSink(), execution.getRunId(), stepNumber,
                    AgentStreamEventType.THOUGHT,
                    thought.getThinking());

            if (thought.isFinished()) {
                return LoopStepDecision.terminate(finalizeSuccess(execution, stepNumber, thought.getStructuredFinalAnswer()));
            }

            ActionDecision action = thought.getAction();
            loopState(execution).getHistory().add(Step.action(action));
            emitEvent(loopState(execution).getEventSink(), execution.getRunId(), stepNumber,
                    AgentStreamEventType.ACTION,
                    actionToEventData(action));
            log.info("Agent执行行动: tool={}, input={}", action.getToolName(), JSON.toJSONString(action.getInput()));

            SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action,
                    loopState(execution).getHistory());
            if (actionAssessment.isBlocked()) {
                applySyntheticObservation(execution, stepNumber, action, actionAssessment.message(), "SAFETY_RULE");
                recordSafetyBlock(execution.getRunId(), loopState(execution).getCustomerId(),
                        loopState(execution).getDoctorId(), execution.getGoal(), actionAssessment, action);
                sessionService.updateSession(execution.getRunId(), loopState(execution).getHistory(),
                        loopState(execution).getMessages(), loopState(execution).getContext(), stepNumber);
                return LoopStepDecision.continueLoop();
            }

            if (isRepeatedAction(loopState(execution).getContext(), loopState(execution).getHistory(), action)) {
                applySyntheticObservation(execution, stepNumber, action, buildDuplicateActionObservation(action),
                        "DUPLICATE_ACTION_GUARD");
                agentMetrics.recordDuplicateToolSuppression(action.getToolName());
                sessionService.updateSession(execution.getRunId(), loopState(execution).getHistory(),
                        loopState(execution).getMessages(), loopState(execution).getContext(), stepNumber);
                return LoopStepDecision.continueLoop();
            }

            ActionResult actionResult = actionExecutor.execute(
                    action,
                    loopState(execution).getCustomerId(),
                    loopState(execution).getDoctorId(),
                    execution.getRunId(),
                    accessContext);
            recordToolExecution(execution.getRunId(), action.getToolName());

            if (actionResult.isNeedsApproval()) {
                return LoopStepDecision.terminate(finalizePendingApproval(execution, stepNumber, action, actionResult));
            }

            String observation = contextMutationService.formatObservation(actionResult);
            loopState(execution).getHistory().add(Step.observation(observation));
            loopState(execution).getMessages().add(Message.user(ReActPromptHistory.OBSERVATION_PREFIX + observation));
            contextMutationService.applyActionResult(loopState(execution).getContext(), action, actionResult, observation);
            emitContextUpdated(execution.getRunId(), stepNumber, loopState(execution).getContext(),
                    action.getToolName(), loopState(execution).getEventSink());
            emitEvent(loopState(execution).getEventSink(), execution.getRunId(), stepNumber,
                    AgentStreamEventType.OBSERVATION, observation);
            log.info("Agent观察结果: {}", observation);
            sessionService.updateSession(execution.getRunId(), loopState(execution).getHistory(),
                    loopState(execution).getMessages(), loopState(execution).getContext(), stepNumber);
            return LoopStepDecision.continueLoop();
        }

        @Override
        protected LoopOutcome<ReActLoopState, AgentResult> onStepException(
                LoopContext<ReActLoopState> loopContext,
                int step,
                int stepNumber,
                Exception e) {
            return finalizeError(loopContext, stepNumber, e);
        }

        @Override
        protected LoopOutcome<ReActLoopState, AgentResult> onMaxSteps(LoopContext<ReActLoopState> loopContext) {
            return finalizeMaxSteps(loopContext);
        }
    }

    private ReActLoopState loopState(LoopContext<ReActLoopState> execution) {
        return execution.getState();
    }

    private LoopOutcome<ReActLoopState, AgentResult> buildLoopOutcome(
            LoopContext<ReActLoopState> execution,
            LoopTerminationReason terminationReason,
            RunStatus runStatus,
            int completedSteps,
            AgentResult result,
            String errorMessage) {
        return LoopOutcome.<ReActLoopState, AgentResult>builder()
                .runId(execution.getRunId())
                .terminationReason(terminationReason)
                .runStatus(runStatus)
                .completedSteps(completedSteps)
                .result(result)
                .state(loopState(execution))
                .errorMessage(errorMessage)
                .endedAt(System.currentTimeMillis())
                .build();
    }

    private ParsedThought requestThoughtResult(
            String systemPrompt,
            List<Message> messages,
            int step,
            String customerId,
            AgentContext context) {
        if (useMock) {
            String mockResponse = mockDeepSeekResponse(step, customerId);
            return new ParsedThought(mockResponse, responseParser.parse(mockResponse));
        }

        String promptWithContext = buildSystemPromptWithContext(systemPrompt, context);
        String responseText = deepSeekClient.chat(promptWithContext, messages).getContent();
        try {
            return new ParsedThought(responseText, responseParser.parse(responseText));
        } catch (AgentException parseException) {
            log.warn("Agent响应协议不合法，准备纠偏重试: {}", parseException.getMessage());
            List<Message> repairMessages = new ArrayList<>(messages);
            repairMessages.add(Message.assistant(responseText));
            repairMessages.add(Message.user(promptBuilder.renderProtocolRepairPrompt(parseException.getMessage()).getContent()));

            AgentException lastException = parseException;
            for (int attempt = 0; attempt < protocolRepairAttempts; attempt++) {
                String repairedResponse = deepSeekClient.chat(promptWithContext, repairMessages).getContent();
                try {
                    return new ParsedThought(repairedResponse, responseParser.parse(repairedResponse));
                } catch (AgentException repairException) {
                    lastException = repairException;
                    repairMessages.add(Message.assistant(repairedResponse));
                    repairMessages
                            .add(Message.user(promptBuilder.renderProtocolRepairPrompt(repairException.getMessage()).getContent()));
                }
            }
            throw lastException;
        }
    }

    private ParsedThought requestThoughtResultStream(
            String systemPrompt,
            List<Message> messages,
            int step,
            String customerId,
            String sessionId,
            Consumer<AgentStreamEvent> eventSink,
            int eventStep,
            AgentContext context) {
        if (useMock) {
            String mockResponse = mockDeepSeekResponse(step, customerId);
            emitEvent(eventSink, sessionId, eventStep, AgentStreamEventType.THINKING_DELTA, mockResponse);
            return new ParsedThought(mockResponse, responseParser.parse(mockResponse));
        }

        String promptWithContext = buildSystemPromptWithContext(systemPrompt, context);
        String responseText = deepSeekClient.chatStream(promptWithContext, messages,
                delta -> emitEvent(eventSink, sessionId, eventStep, AgentStreamEventType.THINKING_DELTA, delta))
                .getContent();
        try {
            return new ParsedThought(responseText, responseParser.parse(responseText));
        } catch (AgentException parseException) {
            log.warn("Agent响应协议不合法，准备纠偏重试: {}", parseException.getMessage());
            List<Message> repairMessages = new ArrayList<>(messages);
            repairMessages.add(Message.assistant(responseText));
            repairMessages.add(Message.user(promptBuilder.renderProtocolRepairPrompt(parseException.getMessage()).getContent()));

            AgentException lastException = parseException;
            for (int attempt = 0; attempt < protocolRepairAttempts; attempt++) {
                String repairedResponse = deepSeekClient.chatStream(promptWithContext, repairMessages,
                        delta -> emitEvent(eventSink, sessionId, eventStep, AgentStreamEventType.THINKING_DELTA, delta))
                        .getContent();
                try {
                    return new ParsedThought(repairedResponse, responseParser.parse(repairedResponse));
                } catch (AgentException repairException) {
                    lastException = repairException;
                    repairMessages.add(Message.assistant(repairedResponse));
                    repairMessages
                            .add(Message.user(promptBuilder.renderProtocolRepairPrompt(repairException.getMessage()).getContent()));
                }
            }
            throw lastException;
        }
    }

    private String buildSystemPromptWithContext(String baseSystemPrompt, AgentContext context) {
        if (context == null) {
            return baseSystemPrompt;
        }
        String contextSection = promptBuilder != null ? promptBuilder.buildContextPromptSection(context) : null;
        if (contextSection == null || contextSection.isBlank()) {
            contextSection = context.toPromptSection();
        }
        return baseSystemPrompt + """

                ## Current Structured Context
                %s
                """.formatted(contextSection);
    }

    private AgentSubAgent selectSubAgent(String runId, String goal, AgentContext context) {
        if (subAgentCoordinator == null) {
            return new AgentSubAgent() {
                @Override
                public String name() {
                    return "DefaultAgent";
                }

                @Override
                public String responsibility() {
                    return "执行默认单Agent推理。";
                }
            };
        }
        AgentSubAgent plannedSubAgent = consumePlannedSubAgent(context);
        if (plannedSubAgent != null) {
            return plannedSubAgent;
        }

        AgentSelectionDecision decision = subAgentCoordinator.selectDecision(goal, context);
        return applySelectionDecision(runId, context, decision);
    }

    private AgentSubAgent applySelectionDecision(String runId, AgentContext context, AgentSelectionDecision decision) {
        if (decision == null || decision.getSelectedAgentIds() == null || decision.getSelectedAgentIds().isEmpty()) {
            throw new IllegalStateException("Selector returned empty agent decision");
        }

        List<String> selectedAgentIds = new ArrayList<>(decision.getSelectedAgentIds());
        recordSelectionDecision(context, decision, selectedAgentIds);
        recordSelectionSnapshot(runId, context, decision, selectedAgentIds);
        String primaryAgentId = selectedAgentIds.get(0);
        List<String> pendingAgentIds = selectedAgentIds.size() > 1
                ? new ArrayList<>(selectedAgentIds.subList(1, selectedAgentIds.size()))
                : List.of();
        context.putSlot(SLOT_SUB_AGENT_PENDING_PLAN, pendingAgentIds, "SELECTOR_DECISION",
                PlatformContextVisibility.AGENT_INTERNAL);
        recordExecutedPlan(context, primaryAgentId, "SELECTOR_PRIMARY");
        return subAgentCoordinator.requiredSubAgent(primaryAgentId);
    }

    private AgentSubAgent consumePlannedSubAgent(AgentContext context) {
        List<String> pendingAgentIds = slotValueAsStringList(context, SLOT_SUB_AGENT_PENDING_PLAN);
        if (pendingAgentIds.isEmpty()) {
            return null;
        }
        String nextAgentId = pendingAgentIds.get(0);
        List<String> remainingAgentIds = pendingAgentIds.size() > 1
                ? new ArrayList<>(pendingAgentIds.subList(1, pendingAgentIds.size()))
                : List.of();
        context.putSlot(SLOT_SUB_AGENT_PENDING_PLAN, remainingAgentIds, "SELECTOR_PLAN_CONSUME",
                PlatformContextVisibility.AGENT_INTERNAL);
        recordExecutedPlan(context, nextAgentId, "SELECTOR_PLAN_CONSUME");
        return subAgentCoordinator.requiredSubAgent(nextAgentId);
    }

    private void recordSelectionDecision(
            AgentContext context,
            AgentSelectionDecision decision,
            List<String> selectedAgentIds) {
        if (context == null || decision == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", decision.getMode() != null ? decision.getMode().name() : AgentSelectionMode.SERIAL.name());
        payload.put("selectedAgentIds", selectedAgentIds);
        payload.put("reasons", decision.getReasons() != null ? new ArrayList<>(decision.getReasons()) : List.of());
        context.putSlot(SLOT_SUB_AGENT_SELECTION_DECISION, payload, "SELECTOR_DECISION",
                PlatformContextVisibility.AGENT_INTERNAL);
    }

    private void recordExecutedPlan(AgentContext context, String agentId, String source) {
        if (context == null || agentId == null || agentId.isBlank()) {
            return;
        }
        List<String> executedAgentIds = new ArrayList<>(slotValueAsStringList(context, SLOT_SUB_AGENT_EXECUTED_PLAN));
        executedAgentIds.add(agentId);
        context.putSlot(SLOT_SUB_AGENT_EXECUTED_PLAN, executedAgentIds, source,
                PlatformContextVisibility.AGENT_INTERNAL);
    }

    private List<String> slotValueAsStringList(AgentContext context, String slotName) {
        if (context == null || context.getSlots() == null || slotName == null || slotName.isBlank()) {
            return List.of();
        }
        ContextEntry entry = context.getSlots().get(slotName);
        if (entry == null || !(entry.getValue() instanceof List<?> values)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    private void switchSubAgent(
            String sessionId,
            int step,
            AgentContext context,
            AgentSubAgent nextSubAgent,
            Consumer<AgentStreamEvent> eventSink) {
        String previous = currentSubAgentName(context);
        String next = nextSubAgent.name();
        if (previous != null && !previous.equals(next)) {
            emitEvent(eventSink, sessionId, step, AgentStreamEventType.SUBAGENT_FINISHED, Map.of("name", previous));
        }
        if (!next.equals(previous)) {
            PlatformAgentHandoff handoff = buildSubAgentHandoff(
                    sessionId,
                    previous,
                    nextSubAgent,
                    projectContextForSubAgent(context, nextSubAgent));
            context.putSlot("sub_agent_handoff", handoff, "coordinator");
            emitEvent(eventSink, sessionId, step, AgentStreamEventType.SUBAGENT_STARTED, Map.of(
                    "name", next,
                    "responsibility", nextSubAgent.responsibility(),
                    "handoff", handoff
            ));
        }
        context.putSlot("current_sub_agent", next, "coordinator");
    }

    private void emitSubAgentFinished(
            String sessionId,
            int step,
            AgentContext context,
            Consumer<AgentStreamEvent> eventSink) {
        String current = currentSubAgentName(context);
        if (current == null) {
            return;
        }
        emitEvent(eventSink, sessionId, step, AgentStreamEventType.SUBAGENT_FINISHED, Map.of("name", current));
        context.putSlot("current_sub_agent", null, "coordinator");
    }

    private void emitContextUpdated(
            String sessionId,
            int step,
            AgentContext context,
            String reason,
            Consumer<AgentStreamEvent> eventSink) {
        emitEvent(eventSink, sessionId, step, AgentStreamEventType.CONTEXT_UPDATED, Map.of(
                "reason", reason,
                "slots", context.getSlots().keySet()
        ));
    }

    private void recordInitialStrategySnapshot(String runId, AgentContext context, AgentExecutionCommand command) {
        if (strategySnapshotRecorder == null || runId == null) {
            return;
        }
        strategySnapshotRecorder.createOrUpdate(AgentRunStrategySnapshot.builder()
                .runId(runId)
                .sessionId(runId)
                .scene(ReActPromptBuilder.DOCTOR_SCENE)
                .taskType(resolveTaskTypeName(context, command))
                .promptVersion(promptBuilder.currentPromptVersion(PromptTemplateType.SUB_AGENT_SYSTEM))
                .selectorVersion(SELECTOR_VERSION)
                .toolCatalogVersion(TOOL_CATALOG_VERSION)
                .memoryProfile(buildMemoryProfile(context, command))
                .memoryRetrievalCount(resolveMemoryRetrievalCount(context, command))
                .memoryRerankApplied(Boolean.FALSE)
                .parallelEnabled(Boolean.FALSE)
                .fallbackAllowed(resolveFallbackAllowed(command))
                .timeoutMillis(resolveTimeoutMillis(command))
                .patientVisibleOnly(resolvePatientVisibleOnly(command))
                .build());
    }

    private void recordSelectionSnapshot(
            String runId,
            AgentContext context,
            AgentSelectionDecision decision,
            List<String> selectedAgentIds) {
        if (strategySnapshotRecorder == null || runId == null || decision == null) {
            return;
        }
        strategySnapshotRecorder.createOrUpdate(AgentRunStrategySnapshot.builder()
                .runId(runId)
                .sessionId(runId)
                .scene(ReActPromptBuilder.DOCTOR_SCENE)
                .taskType(resolveTaskTypeName(context, null))
                .selectorVersion(SELECTOR_VERSION)
                .selectedAgentIds(selectedAgentIds)
                .selectionMode(decision.getMode() != null ? decision.getMode().name() : null)
                .parallelEnabled(decision.getMode() == AgentSelectionMode.PARALLEL)
                .build());
    }

    private void recordRenderedPromptSnapshot(String runId, AgentContext context, RenderedPrompt renderedPrompt) {
        if (strategySnapshotRecorder == null || runId == null || renderedPrompt == null || renderedPrompt.getDescriptor() == null) {
            return;
        }
        strategySnapshotRecorder.createOrUpdate(AgentRunStrategySnapshot.builder()
                .runId(runId)
                .sessionId(runId)
                .scene(renderedPrompt.getDescriptor().getScene() != null
                        ? renderedPrompt.getDescriptor().getScene()
                        : ReActPromptBuilder.DOCTOR_SCENE)
                .taskType(resolveTaskTypeName(context, null))
                .promptVersion(renderedPrompt.getDescriptor().getVersion())
                .build());
    }

    private void recordToolExecution(String runId, String toolName) {
        if (strategySnapshotRecorder == null || runId == null || toolName == null || toolName.isBlank()) {
            return;
        }
        List<String> toolSequence = new ArrayList<>(strategySnapshotRecorder.findByRunId(runId)
                .map(snapshot -> snapshot.getToolSequence() != null ? snapshot.getToolSequence() : List.<String>of())
                .orElse(List.of()));
        toolSequence.add(toolName);
        strategySnapshotRecorder.createOrUpdate(AgentRunStrategySnapshot.builder()
                .runId(runId)
                .sessionId(runId)
                .toolSequence(toolSequence)
                .build());
    }

    private void recordOutcome(
            String runId,
            AgentContext context,
            AgentResult result,
            long durationMs,
            AgentOutcomeLabel outcomeLabel,
            String errorCode,
            String errorMessage) {
        if (outcomeRecorder == null || runId == null || result == null) {
            return;
        }
        outcomeRecorder.createOrUpdate(AgentRunOutcome.builder()
                .runId(runId)
                .sessionId(runId)
                .scene(ReActPromptBuilder.DOCTOR_SCENE)
                .taskType(resolveTaskTypeName(context, null))
                .customerId(context != null ? context.getCustomerId() : null)
                .doctorId(context != null ? context.getDoctorId() : null)
                .finalStatus(result.getStatus() != null ? result.getStatus().name() : "UNKNOWN")
                .outcomeLabel(outcomeLabel != null ? outcomeLabel : AgentOutcomeLabel.FAILED_RUNTIME)
                .fallbackUsed(Boolean.FALSE)
                .manualReviewRequired(result.getStatus() == ResultStatus.PENDING_APPROVAL)
                .manualOverride(Boolean.FALSE)
                .doctorAccepted(null)
                .doctorRejected(null)
                .evidenceSufficient(null)
                .toolErrorCount(0)
                .retryCount(0)
                .durationMs(durationMs)
                .errorCode(errorCode)
                .errorMessage(errorMessage != null ? errorMessage : result.getErrorMessage())
                .build());
        if (attributionService != null) {
            attributionService.recompute(runId);
        }
    }

    private AgentOutcomeLabel classifyFailureOutcomeLabel(Exception exception) {
        if (exception == null) {
            return AgentOutcomeLabel.FAILED_RUNTIME;
        }
        String message = exception.getMessage() != null ? exception.getMessage().toLowerCase() : "";
        if (message.contains("timeout")) {
            return AgentOutcomeLabel.FAILED_TIMEOUT;
        }
        if (exception instanceof AgentException) {
            return AgentOutcomeLabel.FAILED_PROTOCOL_ERROR;
        }
        return AgentOutcomeLabel.FAILED_RUNTIME;
    }

    private String resolveTaskTypeName(AgentContext context, AgentExecutionCommand command) {
        if (command != null && command.getTaskType() != null) {
            return command.getTaskType().name();
        }
        Map<String, Object> patientTaskRequest = slotMapValue(context, "patient_task_request");
        if (patientTaskRequest != null && patientTaskRequest.get("taskType") != null) {
            return String.valueOf(patientTaskRequest.get("taskType"));
        }
        return null;
    }

    private Map<String, Object> buildMemoryProfile(AgentContext context, AgentExecutionCommand command) {
        Map<String, Object> profile = new LinkedHashMap<>();
        com.health.agent.dto.patient.MemorySnapshot snapshot = command != null
                ? command.getMemorySnapshot()
                : slotMemorySnapshot(context, "memory_snapshot");
        profile.put("hasMemorySnapshot", snapshot != null);
        profile.put("hasShortTermMemories", snapshot != null && snapshot.getShortTermMemories() != null
                && !snapshot.getShortTermMemories().isEmpty());
        profile.put("hasLongTermMemories", snapshot != null && snapshot.getLongTermMemories() != null
                && !snapshot.getLongTermMemories().isEmpty());
        profile.put("hasChatHistory", hasSlot(context, "chat_history"));
        profile.put("hasProfileSnapshot", hasSlot(context, "profile_snapshot") || hasSlot(context, "patient_profile"));
        profile.put("shortTermSummary", summarizeList(snapshot != null ? snapshot.getShortTermMemories() : null, 3, 80));
        profile.put("longTermSummary", summarizeList(snapshot != null ? snapshot.getLongTermMemories() : null, 3, 80));
        profile.put("recentFollowUpSignalsSummary",
                summarizeList(slotListValue(context, "recent_follow_up_signals"), 3, 80));
        return profile;
    }

    private Integer resolveMemoryRetrievalCount(AgentContext context, AgentExecutionCommand command) {
        com.health.agent.dto.patient.MemorySnapshot snapshot = command != null
                ? command.getMemorySnapshot()
                : slotMemorySnapshot(context, "memory_snapshot");
        if (snapshot == null) {
            return null;
        }
        int shortTerm = snapshot.getShortTermMemories() != null ? snapshot.getShortTermMemories().size() : 0;
        int longTerm = snapshot.getLongTermMemories() != null ? snapshot.getLongTermMemories().size() : 0;
        return shortTerm + longTerm;
    }

    private Boolean resolveFallbackAllowed(AgentExecutionCommand command) {
        if (command == null || command.getConstraints() == null) {
            return null;
        }
        return command.getConstraints().getFallbackToDirectChatOnFailure();
    }

    private Integer resolveTimeoutMillis(AgentExecutionCommand command) {
        if (command == null || command.getConstraints() == null) {
            return null;
        }
        return command.getConstraints().getTimeoutMillis();
    }

    private Boolean resolvePatientVisibleOnly(AgentExecutionCommand command) {
        if (command == null || command.getConstraints() == null) {
            return null;
        }
        return command.getConstraints().getPatientVisibleResponseOnly();
    }

    private boolean hasSlot(AgentContext context, String slotKey) {
        return context != null && context.getSlots() != null && context.getSlots().containsKey(slotKey);
    }

    private List<Object> slotListValue(AgentContext context, String slotKey) {
        if (context == null || context.getSlots() == null || slotKey == null) {
            return null;
        }
        ContextEntry entry = context.getSlots().get(slotKey);
        if (entry == null || entry.getValue() == null) {
            return null;
        }
        Object value = entry.getValue();
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of(value);
    }

    private String summarizeList(List<?> values, int maxItems, int maxItemChars) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .limit(Math.max(1, maxItems))
                .map(value -> "- " + abbreviate(String.valueOf(value), maxItemChars))
                .collect(Collectors.joining("\n"));
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int safeMax = Math.max(16, maxChars);
        if (text.length() <= safeMax) {
            return text;
        }
        return text.substring(0, safeMax) + "...";
    }

    private String currentSubAgentName(AgentContext context) {
        if (context == null || context.getSlots() == null) {
            return null;
        }
        ContextEntry entry = context.getSlots().get("current_sub_agent");
        if (entry == null || entry.getValue() == null) {
            return null;
        }
        return String.valueOf(entry.getValue());
    }

    private AgentContext projectContextForSubAgent(AgentContext context, AgentSubAgent subAgent) {
        if (context == null || subAgent == null) {
            return context;
        }
        AgentContext projected = AgentContext.initialize(
                context.getCustomerId(),
                context.getDoctorId(),
                sanitizeSummary("goal", context.getGoal()));
        if (context.getSlots() != null) {
            for (Map.Entry<String, ContextEntry> entry : context.getSlots().entrySet()) {
                if (!isSlotVisibleToSubAgent(entry.getKey(), entry.getValue(), subAgent)) {
                    continue;
                }
                ContextEntry slot = entry.getValue();
                projected.putSlot(
                        entry.getKey(),
                        sanitizeValue(entry.getKey(), slot.getValue()),
                        slot.getSource(),
                        slot.getVisibility());
            }
        }
        if (context.getEvidenceRecords() != null) {
            for (EvidenceRecord record : context.getEvidenceRecords()) {
                if (!isEvidenceVisibleToSubAgent(record, subAgent)) {
                    continue;
                }
                projected.addEvidence(
                        record.getToolName(),
                        record.getSource(),
                        sanitizeSummary(record.getToolName(), record.getSummary()),
                        sanitizeValue(record.getToolName(), record.getPayload()),
                        record.getVisibility());
            }
        }
        return projected;
    }

    private boolean isSlotVisibleToSubAgent(String slotKey, ContextEntry entry, AgentSubAgent subAgent) {
        if (slotKey == null || entry == null || subAgent == null) {
            return false;
        }
        PlatformContextVisibility visibility = entry.getVisibility() != null
                ? entry.getVisibility()
                : PlatformContextVisibility.AGENT_INTERNAL;
        if (!subAgent.visibleVisibilities().contains(visibility)) {
            return false;
        }
        return subAgent.visibleSlotKeys().isEmpty() || subAgent.visibleSlotKeys().contains(slotKey);
    }

    private boolean isEvidenceVisibleToSubAgent(EvidenceRecord record, AgentSubAgent subAgent) {
        if (record == null || subAgent == null) {
            return false;
        }
        PlatformContextVisibility visibility = record.getVisibility() != null
                ? record.getVisibility()
                : PlatformContextVisibility.AGENT_INTERNAL;
        if (!subAgent.visibleVisibilities().contains(visibility)) {
            return false;
        }
        String evidenceKey = record.getToolName();
        return evidenceKey == null
                || subAgent.visibleSlotKeys().isEmpty()
                || subAgent.visibleSlotKeys().contains(evidenceKey);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> slotMapValue(AgentContext context, String slotKey) {
        if (context == null || context.getSlots() == null) {
            return null;
        }
        ContextEntry entry = context.getSlots().get(slotKey);
        if (entry == null || !(entry.getValue() instanceof Map<?, ?> value)) {
            return null;
        }
        return (Map<String, Object>) value;
    }

    private com.health.agent.dto.patient.MemorySnapshot slotMemorySnapshot(AgentContext context, String slotKey) {
        if (context == null || context.getSlots() == null) {
            return null;
        }
        ContextEntry entry = context.getSlots().get(slotKey);
        if (entry == null || entry.getValue() == null) {
            return null;
        }
        if (entry.getValue() instanceof com.health.agent.dto.patient.MemorySnapshot snapshot) {
            return snapshot;
        }
        return JSON.parseObject(JSON.toJSONString(entry.getValue()), com.health.agent.dto.patient.MemorySnapshot.class);
    }

    private AgentAccessContext buildToolAccessContext(AgentContext context) {
        Map<String, Object> sessionContext = slotMapValue(context, "session_context");
        Map<String, Object> patientTaskRequest = slotMapValue(context, "patient_task_request");
        return AgentAccessContext.builder()
                .groupId(stringValue(
                        sessionContext != null ? sessionContext.get("groupId") : null,
                        patientTaskRequest != null ? patientTaskRequest.get("groupId") : null))
                .userId(stringValue(
                        sessionContext != null ? sessionContext.get("userId") : null,
                        patientTaskRequest != null ? patientTaskRequest.get("userId") : null,
                        context != null ? context.getDoctorId() : null))
                .tenantId(stringValue(
                        sessionContext != null ? sessionContext.get("tenantId") : null,
                        patientTaskRequest != null ? patientTaskRequest.get("tenantId") : null))
                .build();
    }

    private String stringValue(Object... candidates) {
        if (candidates == null) {
            return null;
        }
        for (Object candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String text = String.valueOf(candidate).trim();
            if (!text.isEmpty() && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return null;
    }

    private Object sanitizeValue(String key, Object value) {
        if (contextPrivacySanitizer == null) {
            return value;
        }
        return contextPrivacySanitizer.sanitizeForExposure(key, value);
    }

    private String sanitizeSummary(String key, String summary) {
        if (contextPrivacySanitizer == null) {
            return summary;
        }
        return contextPrivacySanitizer.sanitizeSummary(key, summary);
    }

    private PlatformAgentHandoff buildSubAgentHandoff(
            String sessionId,
            String previousSubAgentName,
            AgentSubAgent nextSubAgent,
            AgentContext context) {
        List<PlatformAgentHandoffItem> items = new ArrayList<>();
        if (context != null && context.getSlots() != null) {
            for (Map.Entry<String, ContextEntry> entry : context.getSlots().entrySet()) {
                if (items.size() >= 8) {
                    break;
                }
                if ("current_sub_agent".equals(entry.getKey()) || "sub_agent_handoff".equals(entry.getKey())) {
                    continue;
                }
                ContextEntry slot = entry.getValue();
                if (slot == null || slot.getValue() == null) {
                    continue;
                }
                items.add(PlatformAgentHandoffItem.builder()
                        .key(entry.getKey())
                        .type(PlatformHandoffItemType.CONTEXT_SLOT)
                        .summary(abbreviateForHandoff(slot.getValue(), 180))
                        .payload(slot.getValue())
                        .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                        .sourceAgentId(slot.getSource())
                        .metadata(handoffMetadata(
                                "updatedAt", slot.getUpdatedAt(),
                                "source", slot.getSource()))
                        .build());
            }
        }
        if (context != null && context.getEvidenceRecords() != null) {
            context.getEvidenceRecords().stream()
                    .filter(record -> record != null && (record.getSummary() != null || record.getPayload() != null))
                    .limit(3)
                    .forEach(record -> items.add(PlatformAgentHandoffItem.builder()
                            .key(record.getToolName() != null ? record.getToolName() : "evidence")
                            .type(PlatformHandoffItemType.EVIDENCE)
                            .summary(abbreviateForHandoff(
                                    record.getSummary() != null ? record.getSummary() : record.getPayload(), 180))
                            .payload(record.getPayload())
                            .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                            .sourceAgentId(record.getSource())
                            .sourceToolName(record.getToolName())
                            .metadata(handoffMetadata("recordedAt", record.getRecordedAt()))
                            .build()));
        }
        return PlatformAgentHandoff.builder()
                .schemaVersion("agent-handoff/v1")
                .handoffId(sessionId + "-" + nextSubAgent.agentId() + "-" + System.currentTimeMillis())
                .runId(sessionId)
                .fromAgentId(previousSubAgentName)
                .fromAgentName(previousSubAgentName)
                .toAgentId(nextSubAgent.agentId())
                .toAgentName(nextSubAgent.name())
                .goal(context != null ? context.getGoal() : null)
                .handoffSummary(summarizeContextForHandoff(context, nextSubAgent, items))
                .createdAt(java.time.LocalDateTime.now())
                .items(items)
                .build();
    }

    private String summarizeContextForHandoff(
            AgentContext context,
            AgentSubAgent nextSubAgent,
            List<PlatformAgentHandoffItem> items) {
        String goal = context != null ? context.getGoal() : null;
        String target = nextSubAgent != null ? nextSubAgent.name() : "next-agent";
        return "向%s交接%d项上下文，目标=%s".formatted(
                target,
                items != null ? items.size() : 0,
                abbreviateForHandoff(goal, 80));
    }

    private String abbreviateForHandoff(Object value, int maxChars) {
        if (value == null) {
            return "";
        }
        String text = value instanceof String stringValue ? stringValue : JSON.toJSONString(value);
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(1, maxChars)) + "...";
    }

    private Map<String, Object> handoffMetadata(Object... kvPairs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (kvPairs == null) {
            return metadata;
        }
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            Object key = kvPairs[i];
            Object value = kvPairs[i + 1];
            if (key == null || value == null) {
                continue;
            }
            metadata.put(String.valueOf(key), value);
        }
        return metadata;
    }

    private AgentContext parseContext(String contextJson, String customerId, String doctorId, String goal) {
        if (contextJson == null || contextJson.isBlank()) {
            return AgentContext.initialize(customerId, doctorId, goal);
        }
        try {
            AgentContext context = JSON.parseObject(contextJson, AgentContext.class);
            if (context == null) {
                return AgentContext.initialize(customerId, doctorId, goal);
            }
            if (context.getCustomerId() == null) {
                context.setCustomerId(customerId);
            }
            if (context.getDoctorId() == null) {
                context.setDoctorId(doctorId);
            }
            if (context.getGoal() == null) {
                context.setGoal(goal);
            }
            if (context.getSlots() == null) {
                context.setSlots(new java.util.LinkedHashMap<>());
            }
            if (context.getEvidenceRecords() == null) {
                context.setEvidenceRecords(new ArrayList<>());
            }
            return context;
        } catch (Exception e) {
            log.warn("解析AgentContext失败，回退为空上下文: {}", e.getMessage());
            return AgentContext.initialize(customerId, doctorId, goal);
        }
    }

    private Map<String, Object> snapshotContextSlots(AgentContext context) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (context == null || context.getSlots() == null) {
            return snapshot;
        }
        context.getSlots().forEach((key, entry) -> snapshot.put(key, entry != null ? entry.getValue() : null));
        return snapshot;
    }

    private void recordResumeContextUpdate(
            AgentSession session,
            AgentContext context,
            ActionDecision action,
            Map<String, Object> beforeSlotSnapshot) {
        if (agentAuditService == null || session == null || action == null) {
            return;
        }
        agentAuditService.recordResumeContextUpdate(
                session.getId(),
                session.getCustomerId(),
                session.getDoctorId(),
                session.getGoal(),
                action.getToolName(),
                beforeSlotSnapshot,
                snapshotContextSlots(context));
    }

    private void updateLastToolResult(AgentContext context, ActionDecision action, String observation) {
        if (context == null || action == null) {
            return;
        }
        Map<String, Object> lastToolResult = new LinkedHashMap<>();
        lastToolResult.put("toolName", action.getToolName());
        lastToolResult.put("input", action.getInput());
        lastToolResult.put("observation", observation);
        context.putSlot("last_tool_result", lastToolResult, action.getToolName());
    }

    /**
     * Mock DeepSeek 响应（用于测试）
     */
    private String mockDeepSeekResponse(int step, String customerId) {
        switch (step) {
            case 0:
                return """
                        {
                          "protocolVersion": "v1",
                          "thinking": "我需要先了解患者的基本信息，包括年龄、性别、目标和当前健康状况。",
                          "finished": false,
                          "action": {
                            "toolName": "get_patient_profile",
                            "input": {"customerId": "%s"},
                            "reason": "先获取患者基础资料"
                          }
                        }
                        """.formatted(customerId);

            case 1:
                return """
                        {
                          "protocolVersion": "v1",
                          "thinking": "患者35岁女性，目标减重7kg，有高血压病史。BMI 22.8属于正常范围，但有减重目标。我需要查看体重变化趋势来评估进展。",
                          "finished": false,
                          "action": {
                            "toolName": "get_weight_history",
                            "input": {"customerId": "%s", "days": 30},
                            "reason": "查看体重趋势"
                          }
                        }
                        """.formatted(customerId);

            case 2:
                return """
                        {
                          "protocolVersion": "v1",
                          "thinking": "体重整体趋势良好，从65kg降到62kg，下降了3kg。但我需要了解患者最近的状态和情绪，看看是否有影响减重的因素。",
                          "finished": false,
                          "action": {
                            "toolName": "get_chat_history",
                            "input": {"customerId": "%s", "limit": 10},
                            "reason": "查看近期聊天状态"
                          }
                        }
                        """.formatted(customerId);

            default:
                return """
                        {
                          "protocolVersion": "v1",
                          "thinking": "患者出现焦虑情绪，这可能影响了体重管理。结合高血压病史和减重进展，我现在有足够信息生成综合分析了。",
                          "finished": true,
                          "finalAnswer": {
                            "summary": "患者35岁女性，当前减重进展总体良好，但近期存在焦虑情绪和短期体重反弹。",
                            "keyFindings": ["近30天体重总体下降", "近期存在焦虑情绪", "出现短期体重反弹"],
                            "riskLevel": "MEDIUM",
                            "recommendedActions": ["继续当前减重计划", "关注心理状态", "持续监测血压"],
                            "needsAttention": true,
                            "uncertainties": ["缺少更完整的饮食和运动执行记录"],
                            "supportingEvidence": ["近30天体重总体下降", "聊天中出现焦虑描述"],
                            "evidenceSources": ["WEIGHT_HISTORY", "CHAT_HISTORY"],
                            "inferences": ["焦虑可能影响后续减重执行"]
                          }
                        }
                        """;
        }
    }

    private record ParsedThought(String responseText, ThoughtResult thoughtResult) {
    }

    private Map<String, Object> actionToEventData(ActionDecision action) {
        return Map.of(
                "toolName", action.getToolName(),
                "input", action.getInput(),
                "reason", action.getReason());
    }

    private void emitEvent(
            Consumer<AgentStreamEvent> eventSink,
            String sessionId,
            int step,
            AgentStreamEventType type,
            Object data) {
        if (eventSink == null) {
            return;
        }
        eventSink.accept(AgentStreamEvent.builder()
                .sessionId(sessionId)
                .step(step)
                .type(type)
                .data(data)
                .timestamp(java.time.LocalDateTime.now())
                .build());
    }

    private void emitDone(Consumer<AgentStreamEvent> eventSink, String sessionId, int step, String status) {
        emitEvent(eventSink, sessionId, step, AgentStreamEventType.DONE, Map.of("status", status));
    }

    private Map<String, Object> errorEventData(Exception exception, StructuredFinalAnswer degraded) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", exception != null && exception.getMessage() != null ? exception.getMessage() : "unknown");
        data.put("structuredFinalAnswer", degraded);
        return data;
    }

    private void recordSafetyBlock(
            String sessionId,
            String customerId,
            String doctorId,
            String goal,
            SafetyAssessment assessment,
            Object evidence) {
        agentAuditService.recordSafetyBlock(
                sessionId,
                customerId,
                doctorId,
                goal,
                assessment.category(),
                assessment.message(),
                JSON.toJSONString(evidence));
    }

    private String buildExecutionDegradedSummary(Exception exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("协议")) {
            return "当前模型返回不符合协议，无法安全执行后续步骤。";
        }
        return "当前模型调用失败，无法完成自动分析。";
    }

    private String buildExecutionRuntimeEvidence(Exception exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("协议")) {
            return "模型响应协议校验失败";
        }
        return "模型调用超时或重试耗尽";
    }
}
