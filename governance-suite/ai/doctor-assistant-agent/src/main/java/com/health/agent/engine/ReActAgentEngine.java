package com.health.agent.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.client.DeepSeekClient;
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.engine.subagent.AgentSubAgent;
import com.health.agent.engine.subagent.SubAgentCoordinator;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.AgentStreamEvent;
import com.health.agent.model.AgentStreamEventType;
import com.health.agent.model.ContextEntry;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.Step;
import com.health.agent.model.StructuredFinalAnswer;
import com.health.agent.model.TaskStatus;
import com.health.agent.model.ThoughtResult;
import com.health.agent.model.ToolEvidence;
import com.health.agent.model.ToolExecutionOutput;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.safety.SafetyAssessment;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.AgentSessionService;
import com.health.agent.tool.ToolRegistry;

import lombok.extern.slf4j.Slf4j;

/**
 * ReAct Agent 核心引擎
 */
@Slf4j
@Service
public class ReActAgentEngine {

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private ReActPromptBuilder promptBuilder;

    @Autowired
    private ReActResponseParser responseParser;

    @Autowired
    private ActionExecutor actionExecutor;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentSessionService sessionService;

    @Autowired
    private AgentMetrics agentMetrics;

    @Autowired
    private MedicalSafetyService medicalSafetyService;

    @Autowired
    private AgentAuditService agentAuditService;

    @Autowired
    private SubAgentCoordinator subAgentCoordinator;

    @Value("${agent.max-steps:15}")
    private int maxSteps;

    @Value("${agent.use-mock:false}")
    private boolean useMock;

    @Value("${agent.protocol-repair-attempts:1}")
    private int protocolRepairAttempts;

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
        if (goalAssessment.isBlocked()) {
            context.addEvidence("goal_assessment", "SAFETY_RULE", goalAssessment.message(), Map.of("goal", goal));
            recordSafetyBlock(sessionId, customerId, doctorId, goal, goalAssessment, Map.of("goal", goal));
            sessionService.completeSession(sessionId, history, context);
            AgentResult result = AgentResult.success(
                    sessionId,
                    medicalSafetyService.buildSafetyStructuredAnswer(goalAssessment.message()),
                    history,
                    0);
            agentAuditService.recordSessionResult(sessionId, customerId, doctorId, goal, result, 0,
                    System.currentTimeMillis() - startTime);
            return result;
        }

        messages.add(Message.user(promptBuilder.buildUserPrompt(goal, customerId)));
        return runLoop(new LoopExecution(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                0,
                startTime,
                null,
                context));
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

        AgentSession session = sessionService.createSession(customerId, doctorId, goal);
        String sessionId = String.valueOf(session.getId());

        List<Step> history = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        AgentContext context = createInitialContext(command);

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
            emitEvent(eventSink, sessionId, 0, AgentStreamEventType.FINAL_ANSWER, result.getStructuredFinalAnswer());
            emitDone(eventSink, sessionId, 0, result.getStatus().name());
            agentAuditService.recordSessionResult(sessionId, customerId, doctorId, goal, result, 0,
                    System.currentTimeMillis() - startTime);
            return result;
        }

        messages.add(Message.user(promptBuilder.buildUserPrompt(command)));
        return runLoop(new LoopExecution(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                0,
                startTime,
                eventSink,
                context));
    }

    private AgentContext createInitialContext(AgentExecutionCommand command) {
        String goal = command != null ? command.getGoal() : null;
        String customerId = command != null ? command.getCustomerId() : null;
        String doctorId = command != null ? command.getDoctorId() : null;
        AgentContext context = AgentContext.initialize(customerId, doctorId, goal);
        preloadContext(context, command);
        return context;
    }

    private void preloadContext(AgentContext context, AgentExecutionCommand command) {
        if (context == null || command == null) {
            return;
        }

        Map<String, Object> patientTaskRequest = new java.util.LinkedHashMap<>();
        putIfNotNull(patientTaskRequest, "taskType",
                command.getTaskType() != null ? command.getTaskType().name() : null);
        putIfNotNull(patientTaskRequest, "source",
                command.getSource() != null ? command.getSource().name() : null);
        putIfNotNull(patientTaskRequest, "conversationId", command.getConversationId());
        putIfNotNull(patientTaskRequest, "correlationId", command.getCorrelationId());
        if (!patientTaskRequest.isEmpty()) {
            context.putSlot("patient_task_request", patientTaskRequest, "REQUEST_PRELOAD");
        }

        if (command.getSessionContext() != null && !command.getSessionContext().isEmpty()) {
            context.putSlot("session_context", command.getSessionContext(), "REQUEST_PRELOAD");
            context.addEvidence("session_context", "AI_CHAT_SESSION_CONTEXT",
                    JSON.toJSONString(command.getSessionContext()), command.getSessionContext());
        }
        if (command.getProfileSnapshot() != null && !command.getProfileSnapshot().isEmpty()) {
            context.putSlot("profile_snapshot", command.getProfileSnapshot(), "REQUEST_PRELOAD");
            context.putSlot("patient_profile", command.getProfileSnapshot(), "REQUEST_PRELOAD_NORMALIZED");
            context.addEvidence("profile_snapshot", "PROFILE_SNAPSHOT",
                    JSON.toJSONString(command.getProfileSnapshot()), command.getProfileSnapshot());
        }
        if (command.getMemorySnapshot() != null) {
            context.putSlot("memory_snapshot", command.getMemorySnapshot(), "REQUEST_PRELOAD");
            context.addEvidence("memory_snapshot", "AI_CHAT_MEMORY",
                    JSON.toJSONString(command.getMemorySnapshot()), command.getMemorySnapshot());
            if (command.getMemorySnapshot().getShortTermMemories() != null
                    && !command.getMemorySnapshot().getShortTermMemories().isEmpty()) {
                context.putSlot("chat_history", command.getMemorySnapshot().getShortTermMemories(),
                        "REQUEST_PRELOAD_NORMALIZED");
                context.putSlot("recent_follow_up_signals", command.getMemorySnapshot().getShortTermMemories(),
                        "REQUEST_PRELOAD_NORMALIZED");
            }
        }
        if (command.getTriggerDecision() != null && !command.getTriggerDecision().isEmpty()) {
            context.putSlot("trigger_decision", command.getTriggerDecision(), "REQUEST_PRELOAD");
            context.addEvidence("trigger_decision", "TRIGGER_DECISION",
                    JSON.toJSONString(command.getTriggerDecision()), command.getTriggerDecision());
        }
        if (command.getPatientMessage() != null && !command.getPatientMessage().isBlank()) {
            context.putSlot("patient_message", Map.of(
                    "content", command.getPatientMessage(),
                    "conversationId", defaultString(command.getConversationId()),
                    "correlationId", defaultString(command.getCorrelationId())), "REQUEST_PRELOAD");
            context.addEvidence(
                    "patient_message",
                    "PATIENT_MESSAGE",
                    command.getPatientMessage(),
                    Map.of(
                            "conversationId", defaultString(command.getConversationId()),
                            "correlationId", defaultString(command.getCorrelationId()),
                            "taskType", command.getTaskType() != null ? command.getTaskType().name() : "UNKNOWN"));
        }
        if (command.getPatientMessage() != null && !command.getPatientMessage().isBlank()
                && !context.getSlots().containsKey("recent_follow_up_signals")) {
            context.putSlot("recent_follow_up_signals", List.of(command.getPatientMessage()),
                    "REQUEST_PRELOAD_NORMALIZED");
        }
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
            // 1. 从数据库恢复会话状态
            AgentSession session = sessionService.getById(sessionId);
            if (session == null) {
                throw new AgentException("会话不存在: " + sessionId);
            }

            // 2. 验证会话状态
            if (!SessionStatus.PAUSED.name().equals(session.getStatus())) {
                throw new AgentException("会话状态不正确，当前状态: " + session.getStatus());
            }

            // 3. 获取并验证已批准的任务
            PendingTask task = sessionService.getPendingTask(approvedTaskId);
            if (task == null) {
                throw new AgentException("任务不存在: " + approvedTaskId);
            }

            if (!sessionId.equals(task.getAgentSessionId())) {
                throw new AgentException("任务不属于该会话");
            }

            if (!TaskStatus.APPROVED.name().equals(task.getStatus())) {
                throw new AgentException("任务未批准，当前状态: " + task.getStatus());
            }

            // 4. 恢复执行历史和消息
            List<Step> history = JSON.parseArray(session.getHistoryJson(), Step.class);
            List<Message> messages = JSON.parseArray(session.getMessagesJson(), Message.class);
            AgentContext context = parseContext(session.getContextJson(), session.getCustomerId(),
                    session.getDoctorId(), session.getGoal());
            int currentStep = session.getCurrentStep() != null ? session.getCurrentStep() : 0;

            log.info("恢复会话状态: sessionId={}, currentStep={}, historySize={}",
                    sessionId, currentStep, history.size());

            // 5. 执行已批准的行动
            ActionDecision action = JSON.parseObject(task.getInputJson(), ActionDecision.class);
            log.info("执行已批准的行动: tool={}, input={}",
                    action.getToolName(), JSON.toJSONString(action.getInput()));

            Map<String, Object> beforeSlotSnapshot = snapshotContextSlots(context);
            ActionResult actionResult = actionExecutor.executeApprovedAction(action);

            // 6. 记录 Observation
            String observation = formatObservation(actionResult);
            history.add(Step.observation(observation));
            messages.add(Message.user("Observation: " + observation));
            updateContextAfterAction(context, action, actionResult, observation);
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
            return AgentResult.error(sessionId, e.getMessage(), degraded, new ArrayList<>(), 0);
        }
    }

    public AgentResult resumeStream(String sessionId, Long approvedTaskId, Consumer<AgentStreamEvent> eventSink) {
        long startTime = System.currentTimeMillis();
        log.info("Agent流式恢复执行: sessionId={}, taskId={}", sessionId, approvedTaskId);

        try {
            AgentSession session = sessionService.getById(sessionId);
            if (session == null) {
                throw new AgentException("会话不存在: " + sessionId);
            }

            if (!SessionStatus.PAUSED.name().equals(session.getStatus())) {
                throw new AgentException("会话状态不正确，当前状态: " + session.getStatus());
            }

            PendingTask task = sessionService.getPendingTask(approvedTaskId);
            if (task == null) {
                throw new AgentException("任务不存在: " + approvedTaskId);
            }

            if (!sessionId.equals(task.getAgentSessionId())) {
                throw new AgentException("任务不属于该会话");
            }

            if (!TaskStatus.APPROVED.name().equals(task.getStatus())) {
                throw new AgentException("任务未批准，当前状态: " + task.getStatus());
            }

            List<Step> history = JSON.parseArray(session.getHistoryJson(), Step.class);
            List<Message> messages = JSON.parseArray(session.getMessagesJson(), Message.class);
            AgentContext context = parseContext(session.getContextJson(), session.getCustomerId(), session.getDoctorId(), session.getGoal());
            int currentStep = session.getCurrentStep() != null ? session.getCurrentStep() : 0;

            ActionDecision action = JSON.parseObject(task.getInputJson(), ActionDecision.class);
            Map<String, Object> beforeSlotSnapshot = snapshotContextSlots(context);
            ActionResult actionResult = actionExecutor.executeApprovedAction(action);

            String observation = formatObservation(actionResult);
            history.add(Step.observation(observation));
            messages.add(Message.user("Observation: " + observation));
            emitEvent(eventSink, sessionId, currentStep + 1, AgentStreamEventType.OBSERVATION, observation);
            updateContextAfterAction(context, action, actionResult, observation);
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
            emitEvent(eventSink, sessionId, 0, AgentStreamEventType.ERROR,
                    Map.of("message", e.getMessage(), "structuredFinalAnswer", degraded));
            emitDone(eventSink, sessionId, 0, result.getStatus().name());
            return result;
        }
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
        return runLoop(new LoopExecution(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                startStep,
                System.currentTimeMillis(),
                null,
                context));
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
        return runLoop(new LoopExecution(
                sessionId,
                goal,
                customerId,
                doctorId,
                history,
                messages,
                startStep,
                startTime,
                eventSink,
                context));
    }

    private AgentResult runLoop(LoopExecution execution) {
        for (int step = execution.startStep(); step < maxSteps; step++) {
            int stepNumber = step + 1;
            log.info("Agent执行第{}步", stepNumber);
            try {
                AgentSubAgent subAgent = selectSubAgent(execution.goal(), execution.context());
                switchSubAgent(execution.sessionId(), stepNumber, execution.context(), subAgent, execution.eventSink());
                String systemPrompt = promptBuilder.buildSubAgentSystemPrompt(subAgent, toolRegistry.getAllTools());
                ParsedThought parsedThought = requestThought(execution, systemPrompt, step, stepNumber);
                log.info("Agent响应: {}", parsedThought.responseText());

                ThoughtResult thought = parsedThought.thoughtResult();
                execution.history().add(Step.thought(thought.getThinking()));
                execution.messages().add(Message.assistant(parsedThought.responseText()));
                emitEvent(execution.eventSink(), execution.sessionId(), stepNumber, AgentStreamEventType.THOUGHT,
                        thought.getThinking());

                if (thought.isFinished()) {
                    return finalizeSuccess(execution, stepNumber, thought.getStructuredFinalAnswer());
                }

                ActionDecision action = thought.getAction();
                execution.history().add(Step.action(action));
                emitEvent(execution.eventSink(), execution.sessionId(), stepNumber, AgentStreamEventType.ACTION,
                        actionToEventData(action));
                log.info("Agent执行行动: tool={}, input={}", action.getToolName(), JSON.toJSONString(action.getInput()));

                SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action, execution.history());
                if (actionAssessment.isBlocked()) {
                    applySyntheticObservation(execution, stepNumber, action, actionAssessment.message(), "SAFETY_RULE");
                    recordSafetyBlock(execution.sessionId(), execution.customerId(), execution.doctorId(),
                            execution.goal(), actionAssessment, action);
                    sessionService.updateSession(execution.sessionId(), execution.history(), execution.messages(),
                            execution.context(), stepNumber);
                    continue;
                }

                if (isRepeatedAction(execution.context(), execution.history(), action)) {
                    applySyntheticObservation(execution, stepNumber, action, buildDuplicateActionObservation(action),
                            "DUPLICATE_ACTION_GUARD");
                    agentMetrics.recordDuplicateToolSuppression(action.getToolName());
                    sessionService.updateSession(execution.sessionId(), execution.history(), execution.messages(),
                            execution.context(), stepNumber);
                    continue;
                }

                ActionResult actionResult = actionExecutor.execute(
                        action,
                        execution.customerId(),
                        execution.doctorId(),
                        execution.sessionId());

                if (actionResult.isNeedsApproval()) {
                    return finalizePendingApproval(execution, stepNumber, action, actionResult);
                }

                String observation = formatObservation(actionResult);
                execution.history().add(Step.observation(observation));
                execution.messages().add(Message.user("Observation: " + observation));
                updateContextAfterAction(execution.context(), action, actionResult, observation);
                emitContextUpdated(execution.sessionId(), stepNumber, execution.context(), action.getToolName(),
                        execution.eventSink());
                emitEvent(execution.eventSink(), execution.sessionId(), stepNumber, AgentStreamEventType.OBSERVATION,
                        observation);
                log.info("Agent观察结果: {}", observation);
                sessionService.updateSession(execution.sessionId(), execution.history(), execution.messages(),
                        execution.context(), stepNumber);
            } catch (Exception e) {
                return finalizeError(execution, stepNumber, e);
            }
        }

        return finalizeMaxSteps(execution);
    }

    private ParsedThought requestThought(
            LoopExecution execution,
            String systemPrompt,
            int step,
            int stepNumber) {
        if (execution.eventSink() == null) {
            return requestThoughtResult(systemPrompt, execution.messages(), step, execution.customerId(),
                    execution.context());
        }
        return requestThoughtResultStream(systemPrompt, execution.messages(), step, execution.customerId(),
                execution.sessionId(), execution.eventSink(), stepNumber, execution.context());
    }

    private AgentResult finalizeSuccess(LoopExecution execution, int stepNumber, StructuredFinalAnswer finalAnswer) {
        SafetyAssessment finalAnswerAssessment = medicalSafetyService.assessFinalAnswer(finalAnswer, execution.history());
        sessionService.completeSession(execution.sessionId(), execution.history(), execution.context());
        long duration = System.currentTimeMillis() - execution.startTimeMs();
        agentMetrics.recordExecutionSuccess(stepNumber, duration);

        AgentResult result = AgentResult.success(
                execution.sessionId(),
                finalAnswerAssessment.isBlocked()
                        ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                        : finalAnswer,
                execution.history(),
                stepNumber);
        if (finalAnswerAssessment.isBlocked()) {
            recordSafetyBlock(execution.sessionId(), execution.customerId(), execution.doctorId(), execution.goal(),
                    finalAnswerAssessment, finalAnswer);
        }
        emitEvent(execution.eventSink(), execution.sessionId(), stepNumber, AgentStreamEventType.FINAL_ANSWER,
                result.getStructuredFinalAnswer());
        emitSubAgentFinished(execution.sessionId(), stepNumber, execution.context(), execution.eventSink());
        emitDone(execution.eventSink(), execution.sessionId(), stepNumber, result.getStatus().name());
        agentAuditService.recordSessionResult(execution.sessionId(), execution.customerId(), execution.doctorId(),
                execution.goal(), result, stepNumber, duration);
        return result;
    }

    private AgentResult finalizePendingApproval(
            LoopExecution execution,
            int stepNumber,
            ActionDecision action,
            ActionResult actionResult) {
        log.info("行动需要医生确认，Agent暂停");
        sessionService.pauseSession(execution.sessionId(), execution.history(), execution.messages(), execution.context());
        agentMetrics.recordPendingApproval(action.getToolName());

        AgentResult result = AgentResult.pendingApproval(
                execution.sessionId(),
                actionResult.getTask(),
                execution.history(),
                stepNumber);
        emitEvent(execution.eventSink(), execution.sessionId(), stepNumber, AgentStreamEventType.PENDING_APPROVAL,
                actionResult.getTask());
        emitSubAgentFinished(execution.sessionId(), stepNumber, execution.context(), execution.eventSink());
        emitDone(execution.eventSink(), execution.sessionId(), stepNumber, result.getStatus().name());
        agentAuditService.recordSessionResult(execution.sessionId(), execution.customerId(), execution.doctorId(),
                execution.goal(), result, stepNumber, System.currentTimeMillis() - execution.startTimeMs());
        return result;
    }

    private AgentResult finalizeError(LoopExecution execution, int stepNumber, Exception e) {
        log.error("Agent执行异常", e);
        sessionService.failSession(execution.sessionId(), e.getMessage());
        long duration = System.currentTimeMillis() - execution.startTimeMs();
        agentMetrics.recordExecutionFailure(e.getClass().getSimpleName(), stepNumber, duration);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                buildExecutionDegradedSummary(e),
                "MEDIUM",
                buildExecutionRuntimeEvidence(e),
                List.of("请人工接管当前会话", "补充近期关键数据后再尝试重新分析"),
                List.of("当前未获得可用的自动分析结果"));
        AgentResult result = AgentResult.error(execution.sessionId(), e.getMessage(), degraded, execution.history(),
                stepNumber);
        emitEvent(execution.eventSink(), execution.sessionId(), stepNumber, AgentStreamEventType.ERROR,
                errorEventData(e, degraded));
        emitSubAgentFinished(execution.sessionId(), stepNumber, execution.context(), execution.eventSink());
        emitDone(execution.eventSink(), execution.sessionId(), stepNumber, result.getStatus().name());
        agentAuditService.recordSessionResult(execution.sessionId(), execution.customerId(), execution.doctorId(),
                execution.goal(), result, stepNumber, duration);
        return result;
    }

    private AgentResult finalizeMaxSteps(LoopExecution execution) {
        log.warn("Agent达到最大步数限制");
        sessionService.failSession(execution.sessionId(), "达到最大步数限制");
        long duration = System.currentTimeMillis() - execution.startTimeMs();
        agentMetrics.recordMaxStepsReached(maxSteps, duration);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                "自动分析已达到最大步数，未能收敛到最终结论。",
                "MEDIUM",
                "达到最大步数限制",
                List.of("请人工查看已有 Observation", "必要时缩小问题范围后重新发起分析"),
                List.of("当前分析链路未完成"));
        AgentResult result = AgentResult.maxStepsReached(execution.sessionId(), degraded, execution.history(), maxSteps);
        emitEvent(execution.eventSink(), execution.sessionId(), maxSteps, AgentStreamEventType.FINAL_ANSWER, degraded);
        emitSubAgentFinished(execution.sessionId(), maxSteps, execution.context(), execution.eventSink());
        emitDone(execution.eventSink(), execution.sessionId(), maxSteps, result.getStatus().name());
        agentAuditService.recordSessionResult(execution.sessionId(), execution.customerId(), execution.doctorId(),
                execution.goal(), result, maxSteps, duration);
        return result;
    }

    private void applySyntheticObservation(
            LoopExecution execution,
            int stepNumber,
            ActionDecision action,
            String observation,
            String evidenceSource) {
        execution.history().add(Step.observation(observation));
        execution.messages().add(Message.user("Observation: " + observation));
        execution.context().addEvidence(action.getToolName(), evidenceSource, observation, action.getInput());
        updateLastToolResult(execution.context(), action, observation);
        emitContextUpdated(execution.sessionId(), stepNumber, execution.context(), action.getToolName(),
                execution.eventSink());
        emitEvent(execution.eventSink(), execution.sessionId(), stepNumber, AgentStreamEventType.OBSERVATION,
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

    private record LoopExecution(
            String sessionId,
            String goal,
            String customerId,
            String doctorId,
            List<Step> history,
            List<Message> messages,
            int startStep,
            long startTimeMs,
            Consumer<AgentStreamEvent> eventSink,
            AgentContext context) {
    }

    /**
     * 格式化观察结果
     */
    private String formatObservation(ActionResult result) {
        if (result.isSuccess()) {
            return JSON.toJSONString(result.getOutput());
        } else {
            return "Error: " + result.getErrorMessage();
        }
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
            repairMessages.add(Message.user(promptBuilder.buildProtocolRepairPrompt(parseException.getMessage())));

            AgentException lastException = parseException;
            for (int attempt = 0; attempt < protocolRepairAttempts; attempt++) {
                String repairedResponse = deepSeekClient.chat(promptWithContext, repairMessages).getContent();
                try {
                    return new ParsedThought(repairedResponse, responseParser.parse(repairedResponse));
                } catch (AgentException repairException) {
                    lastException = repairException;
                    repairMessages.add(Message.assistant(repairedResponse));
                    repairMessages
                            .add(Message.user(promptBuilder.buildProtocolRepairPrompt(repairException.getMessage())));
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
            repairMessages.add(Message.user(promptBuilder.buildProtocolRepairPrompt(parseException.getMessage())));

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
                            .add(Message.user(promptBuilder.buildProtocolRepairPrompt(repairException.getMessage())));
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

    private AgentSubAgent selectSubAgent(String goal, AgentContext context) {
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
        return subAgentCoordinator.select(goal, context);
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
            emitEvent(eventSink, sessionId, step, AgentStreamEventType.SUBAGENT_STARTED, Map.of(
                    "name", next,
                    "responsibility", nextSubAgent.responsibility()
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

    private void updateContextAfterAction(
            AgentContext context,
            ActionDecision action,
            ActionResult actionResult,
            String observation) {
        if (context == null || action == null) {
            return;
        }

        Object payload = actionResult != null ? actionResult.getOutput() : observation;
        if (payload instanceof ToolExecutionOutput toolOutput) {
            if (toolOutput.getStateUpdates() != null) {
                toolOutput.getStateUpdates().forEach((key, value) ->
                    context.putSlot(key, value, action.getToolName())
                );
            }
            if (toolOutput.getEvidence() != null) {
                for (ToolEvidence evidence : toolOutput.getEvidence()) {
                    context.addEvidence(
                        action.getToolName(),
                        evidence.getSource(),
                        evidence.getSummary(),
                        evidence.getPayload()
                    );
                }
            }
        } else {
            String slotKey = resolveContextSlot(action.getToolName());
            context.putSlot(slotKey, payload, action.getToolName());
            context.addEvidence(action.getToolName(), "TOOL_OUTPUT", observation, payload);
        }
        updateLastToolResult(context, action, observation);
    }

    private String resolveContextSlot(String toolName) {
        return switch (toolName) {
            case "get_patient_profile" -> "patient_profile";
            case "get_weight_history" -> "weight_history";
            case "get_chat_history" -> "chat_history";
            case "get_prescription_history" -> "prescription_history";
            case "get_follow_up_tasks", "create_follow_up_task" -> "follow_up_tasks";
            case "analyze_weight_trend" -> "weight_trend_analysis";
            case "detect_weight_plateau" -> "plateau_assessment";
            case "analyze_adherence_signals" -> "adherence_analysis";
            case "get_health_knowledge" -> "health_knowledge";
            default -> toolName;
        };
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
