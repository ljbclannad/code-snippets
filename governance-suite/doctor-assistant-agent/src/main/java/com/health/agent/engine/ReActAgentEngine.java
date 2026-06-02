package com.health.agent.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.client.DeepSeekClient;
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

        // 构建初始 User Prompt
        String userPrompt = promptBuilder.buildUserPrompt(goal, customerId);
        messages.add(Message.user(userPrompt));

        for (int step = 0; step < maxSteps; step++) {
            log.info("Agent执行第{}步", step + 1);

            try {
                AgentSubAgent subAgent = selectSubAgent(goal, context);
                switchSubAgent(sessionId, step + 1, context, subAgent, null);
                String systemPrompt = promptBuilder.buildSubAgentSystemPrompt(subAgent, toolRegistry.getAllTools());
                // 1. 调用 DeepSeek API 获取 Thought 和 Action
                ParsedThought parsedThought = requestThoughtResult(systemPrompt, messages, step, customerId, context);
                log.info("Agent响应: {}", parsedThought.responseText());

                // 记录 Thought
                ThoughtResult thought = parsedThought.thoughtResult();
                history.add(Step.thought(thought.getThinking()));
                messages.add(Message.assistant(parsedThought.responseText()));

                // 3. 检查是否完成
                if (thought.isFinished()) {
                    StructuredFinalAnswer finalAnswer = thought.getStructuredFinalAnswer();
                    SafetyAssessment finalAnswerAssessment = medicalSafetyService.assessFinalAnswer(
                            finalAnswer,
                            history);
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history, context);

                    // 记录成功
                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    AgentResult result = AgentResult.success(
                            sessionId,
                            finalAnswerAssessment.isBlocked()
                                    ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                                    : finalAnswer,
                            history,
                            step + 1);
                    emitSubAgentFinished(sessionId, step + 1, context, null);
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                            duration);
                    return result;
                }

                // 4. 执行 Action
                ActionDecision action = thought.getAction();
                history.add(Step.action(action));

                log.info("Agent执行行动: tool={}, input={}",
                        action.getToolName(),
                        JSON.toJSONString(action.getInput()));

                SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action, history);
                if (actionAssessment.isBlocked()) {
                    history.add(Step.observation(actionAssessment.message()));
                    messages.add(Message.user("Observation: " + actionAssessment.message()));
                    context.addEvidence(action.getToolName(), "SAFETY_RULE", actionAssessment.message(),
                            action.getInput());
                    sessionService.updateSession(sessionId, history, messages, context, step + 1);
                    continue;
                }

                ActionResult actionResult = actionExecutor.execute(
                        action,
                        customerId,
                        doctorId,
                        sessionId);

                // 5. 检查是否需要人工确认
                if (actionResult.isNeedsApproval()) {
                    log.info("行动需要医生确认，Agent暂停");
                    sessionService.pauseSession(sessionId, history, messages, context);

                    // 记录等待审批
                    agentMetrics.recordPendingApproval(action.getToolName());

                    AgentResult result = AgentResult.pendingApproval(
                            sessionId,
                            actionResult.getTask(),
                            history,
                            step + 1);
                    emitSubAgentFinished(sessionId, step + 1, context, null);
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                            System.currentTimeMillis() - startTime);
                    return result;
                }

                // 6. 记录 Observation
                String observation = formatObservation(actionResult);
                history.add(Step.observation(observation));
                messages.add(Message.user("Observation: " + observation));
                updateContextAfterAction(context, action, actionResult, observation);
                emitContextUpdated(sessionId, step + 1, context, action.getToolName(), null);

                log.info("Agent观察结果: {}", observation);

                // 7. 更新会话状态
                sessionService.updateSession(sessionId, history, messages, context, step + 1);

            } catch (Exception e) {
                log.error("Agent执行异常", e);
                sessionService.failSession(sessionId, e.getMessage());

                // 记录失败
                long duration = System.currentTimeMillis() - startTime;
                agentMetrics.recordExecutionFailure(e.getClass().getSimpleName(), step + 1, duration);

                StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                        buildExecutionDegradedSummary(e),
                        "MEDIUM",
                        buildExecutionRuntimeEvidence(e),
                        List.of("请人工接管当前会话", "补充近期关键数据后再尝试重新分析"),
                        List.of("当前未获得可用的自动分析结果"));
                AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, history, step + 1);
                emitSubAgentFinished(sessionId, step + 1, context, null);
                agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        System.currentTimeMillis() - startTime);
                return result;
            }
        }

        log.warn("Agent达到最大步数限制");
        sessionService.failSession(sessionId, "达到最大步数限制");

        // 记录达到最大步数
        agentMetrics.recordMaxStepsReached(maxSteps, System.currentTimeMillis() - startTime);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                "自动分析已达到最大步数，未能收敛到最终结论。",
                "MEDIUM",
                "达到最大步数限制",
                List.of("请人工查看已有 Observation", "必要时缩小问题范围后重新发起分析"),
                List.of("当前分析链路未完成"));
        AgentResult result = AgentResult.maxStepsReached(sessionId, degraded, history, maxSteps);
        emitSubAgentFinished(sessionId, maxSteps, context, null);
        agentAuditService.recordSessionResult(
                sessionId,
                customerId,
                doctorId,
                goal,
                result,
                maxSteps,
                System.currentTimeMillis() - startTime);
        return result;
    }

    public AgentResult executeStream(
            String goal,
            String customerId,
            String doctorId,
            Consumer<AgentStreamEvent> eventSink) {
        long startTime = System.currentTimeMillis();
        log.info("Agent开始流式执行任务: goal={}, customerId={}", goal, customerId);

        agentMetrics.recordExecutionStart();

        AgentSession session = sessionService.createSession(customerId, doctorId, goal);
        String sessionId = String.valueOf(session.getId());

        List<Step> history = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        AgentContext context = AgentContext.initialize(customerId, doctorId, goal);

        // 先做目标级安全预检，命中高危规则时直接返回安全结果，不进入模型循环。
        SafetyAssessment goalAssessment = medicalSafetyService.assessGoal(goal);
        if (goalAssessment.isBlocked()) {
            context.addEvidence("goal_assessment", "SAFETY_RULE", goalAssessment.message(), Map.of("goal", goal));
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

        String userPrompt = promptBuilder.buildUserPrompt(goal, customerId);
        messages.add(Message.user(userPrompt));

        for (int step = 0; step < maxSteps; step++) {
            log.info("Agent执行第{}步", step + 1);

            try {
                // 每一轮先由协调器选择当前子代理，再基于其角色职责构建专属 prompt。
                AgentSubAgent subAgent = selectSubAgent(goal, context);
                switchSubAgent(sessionId, step + 1, context, subAgent, eventSink);
                String systemPrompt = promptBuilder.buildSubAgentSystemPrompt(subAgent, toolRegistry.getAllTools());

                // 流式调用模型：前端先收到 thinking_delta，流结束后再解析完整 JSON。
                ParsedThought parsedThought = requestThoughtResultStream(
                        systemPrompt, messages, step, customerId, sessionId, eventSink, step + 1, context);
                log.info("Agent响应: {}", parsedThought.responseText());

                ThoughtResult thought = parsedThought.thoughtResult();
                history.add(Step.thought(thought.getThinking()));
                messages.add(Message.assistant(parsedThought.responseText()));
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.THOUGHT, thought.getThinking());

                // 当模型已经收敛到最终答案时，直接落会话、发 final_answer 并结束流。
                if (thought.isFinished()) {
                    StructuredFinalAnswer finalAnswer = thought.getStructuredFinalAnswer();
                    SafetyAssessment finalAnswerAssessment = medicalSafetyService.assessFinalAnswer(
                            finalAnswer,
                            history);
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history, context);

                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    AgentResult result = AgentResult.success(
                            sessionId,
                            finalAnswerAssessment.isBlocked()
                                    ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                                    : finalAnswer,
                            history,
                            step + 1);
                    emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.FINAL_ANSWER,
                            result.getStructuredFinalAnswer());
                    emitSubAgentFinished(sessionId, step + 1, context, eventSink);
                    emitDone(eventSink, sessionId, step + 1, result.getStatus().name());
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                        duration);
                    return result;
                }

                // 未完成时进入动作阶段，先广播 action，再执行安全校验和工具调用。
                ActionDecision action = thought.getAction();
                history.add(Step.action(action));
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.ACTION, actionToEventData(action));

                log.info("Agent执行行动: tool={}, input={}",
                        action.getToolName(),
                        JSON.toJSONString(action.getInput()));

                SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action, history);
                if (actionAssessment.isBlocked()) {
                    history.add(Step.observation(actionAssessment.message()));
                    messages.add(Message.user("Observation: " + actionAssessment.message()));
                    emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.OBSERVATION,
                            actionAssessment.message());
                    context.addEvidence(action.getToolName(), "SAFETY_RULE", actionAssessment.message(),
                            action.getInput());
                    sessionService.updateSession(sessionId, history, messages, context, step + 1);
                    continue;
                }

                // 工具执行后分两条路：要么进入审批暂停，要么把结果写入显式状态继续推进。
                ActionResult actionResult = actionExecutor.execute(
                        action,
                        customerId,
                        doctorId,
                        sessionId);

                if (actionResult.isNeedsApproval()) {
                    log.info("行动需要医生确认，Agent暂停");
                    sessionService.pauseSession(sessionId, history, messages, context);
                    agentMetrics.recordPendingApproval(action.getToolName());

                    AgentResult result = AgentResult.pendingApproval(
                            sessionId,
                            actionResult.getTask(),
                            history,
                            step + 1);
                    emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.PENDING_APPROVAL,
                            actionResult.getTask());
                    emitSubAgentFinished(sessionId, step + 1, context, eventSink);
                    emitDone(eventSink, sessionId, step + 1, result.getStatus().name());
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                            System.currentTimeMillis() - startTime);
                    return result;
                }

                // 标准化工具输出会在这里汇总成 Observation，同时回写 AgentContext。
                String observation = formatObservation(actionResult);
                history.add(Step.observation(observation));
                messages.add(Message.user("Observation: " + observation));
                updateContextAfterAction(context, action, actionResult, observation);
                emitContextUpdated(sessionId, step + 1, context, action.getToolName(), eventSink);
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.OBSERVATION, observation);

                log.info("Agent观察结果: {}", observation);
                sessionService.updateSession(sessionId, history, messages, context, step + 1);

            } catch (Exception e) {
                // 任一阶段异常都统一转成结构化降级结果，并发 error/done 结束流。
                log.error("Agent执行异常", e);
                sessionService.failSession(sessionId, e.getMessage());

                long duration = System.currentTimeMillis() - startTime;
                agentMetrics.recordExecutionFailure(e.getClass().getSimpleName(), step + 1, duration);

                StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                        buildExecutionDegradedSummary(e),
                        "MEDIUM",
                        buildExecutionRuntimeEvidence(e),
                        List.of("请人工接管当前会话", "补充近期关键数据后再尝试重新分析"),
                        List.of("当前未获得可用的自动分析结果"));
                AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, history, step + 1);
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.ERROR,
                        Map.of("message", e.getMessage(), "structuredFinalAnswer", degraded));
                emitSubAgentFinished(sessionId, step + 1, context, eventSink);
                emitDone(eventSink, sessionId, step + 1, result.getStatus().name());
                agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        duration);
                return result;
            }
        }

        // 超过最大步数仍未完成时，返回保守降级答案，避免无限循环。
        log.warn("Agent达到最大步数限制");
        sessionService.failSession(sessionId, "达到最大步数限制");
        agentMetrics.recordMaxStepsReached(maxSteps, System.currentTimeMillis() - startTime);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                "自动分析已达到最大步数，未能收敛到最终结论。",
                "MEDIUM",
                "达到最大步数限制",
                List.of("请人工查看已有 Observation", "必要时缩小问题范围后重新发起分析"),
                List.of("当前分析链路未完成"));
        AgentResult result = AgentResult.maxStepsReached(sessionId, degraded, history, maxSteps);
        emitEvent(eventSink, sessionId, maxSteps, AgentStreamEventType.FINAL_ANSWER, degraded);
        emitSubAgentFinished(sessionId, maxSteps, context, eventSink);
        emitDone(eventSink, sessionId, maxSteps, result.getStatus().name());
        agentAuditService.recordSessionResult(
                sessionId,
                customerId,
                doctorId,
                goal,
                result,
                maxSteps,
                System.currentTimeMillis() - startTime);
        return result;
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

            ActionResult actionResult = actionExecutor.executeApprovedAction(action);

            // 6. 记录 Observation
            String observation = formatObservation(actionResult);
            history.add(Step.observation(observation));
            messages.add(Message.user("Observation: " + observation));
            updateContextAfterAction(context, action, actionResult, observation);

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
            ActionResult actionResult = actionExecutor.executeApprovedAction(action);

            String observation = formatObservation(actionResult);
            history.add(Step.observation(observation));
            messages.add(Message.user("Observation: " + observation));
            emitEvent(eventSink, sessionId, currentStep + 1, AgentStreamEventType.OBSERVATION, observation);

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
        long startTime = System.currentTimeMillis();
        log.info("继续ReAct循环: sessionId={}, startStep={}", sessionId, startStep);

        for (int step = startStep; step < maxSteps; step++) {
            log.info("Agent执行第{}步", step + 1);

            try {
                AgentSubAgent subAgent = selectSubAgent(goal, context);
                switchSubAgent(sessionId, step + 1, context, subAgent, null);
                String systemPrompt = promptBuilder.buildSubAgentSystemPrompt(subAgent, toolRegistry.getAllTools());
                // 1. 调用 DeepSeek API 获取 Thought 和 Action
                ParsedThought parsedThought = requestThoughtResult(systemPrompt, messages, step, customerId, context);
                log.info("Agent响应: {}", parsedThought.responseText());

                // 记录 Thought
                ThoughtResult thought = parsedThought.thoughtResult();
                history.add(Step.thought(thought.getThinking()));
                messages.add(Message.assistant(parsedThought.responseText()));

                // 3. 检查是否完成
                if (thought.isFinished()) {
                    StructuredFinalAnswer finalAnswer = thought.getStructuredFinalAnswer();
                    SafetyAssessment finalAnswerAssessment = medicalSafetyService.assessFinalAnswer(
                            finalAnswer,
                            history);
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history, context);

                    // 记录成功
                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    AgentResult result = AgentResult.success(
                            sessionId,
                            finalAnswerAssessment.isBlocked()
                                    ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                                    : finalAnswer,
                            history,
                            step + 1);
                    emitSubAgentFinished(sessionId, step + 1, context, null);
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                            duration);
                    return result;
                }

                // 4. 执行 Action
                ActionDecision action = thought.getAction();
                history.add(Step.action(action));

                log.info("Agent执行行动: tool={}, input={}",
                        action.getToolName(),
                        JSON.toJSONString(action.getInput()));

                SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action, history);
                if (actionAssessment.isBlocked()) {
                    history.add(Step.observation(actionAssessment.message()));
                    messages.add(Message.user("Observation: " + actionAssessment.message()));
                    context.addEvidence(action.getToolName(), "SAFETY_RULE", actionAssessment.message(),
                            action.getInput());
                    sessionService.updateSession(sessionId, history, messages, context, step + 1);
                    continue;
                }

                ActionResult actionResult = actionExecutor.execute(
                        action,
                        customerId,
                        doctorId,
                        sessionId);

                // 5. 检查是否需要人工确认
                if (actionResult.isNeedsApproval()) {
                    log.info("行动需要医生确认，Agent暂停");
                    sessionService.pauseSession(sessionId, history, messages, context);

                    // 记录等待审批
                    agentMetrics.recordPendingApproval(action.getToolName());

                    AgentResult result = AgentResult.pendingApproval(
                            sessionId,
                            actionResult.getTask(),
                            history,
                            step + 1);
                    emitSubAgentFinished(sessionId, step + 1, context, null);
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                            System.currentTimeMillis() - startTime);
                    return result;
                }

                // 6. 记录 Observation
                String observation = formatObservation(actionResult);
                history.add(Step.observation(observation));
                messages.add(Message.user("Observation: " + observation));
                updateContextAfterAction(context, action, actionResult, observation);
                emitContextUpdated(sessionId, step + 1, context, action.getToolName(), null);

                log.info("Agent观察结果: {}", observation);

                // 7. 更新会话状态
                sessionService.updateSession(sessionId, history, messages, context, step + 1);

            } catch (Exception e) {
                log.error("Agent执行异常", e);
                sessionService.failSession(sessionId, e.getMessage());

                // 记录失败
                agentMetrics.recordExecutionFailure(e.getClass().getSimpleName());

                StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                        buildExecutionDegradedSummary(e),
                        "MEDIUM",
                        buildExecutionRuntimeEvidence(e),
                        List.of("请人工接管当前会话", "补充近期关键数据后再尝试重新分析"),
                        List.of("当前未获得可用的自动分析结果"));
                AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, history, step + 1);
                emitSubAgentFinished(sessionId, step + 1, context, null);
                agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        System.currentTimeMillis() - startTime);
                return result;
            }
        }

        log.warn("Agent达到最大步数限制");
        sessionService.failSession(sessionId, "达到最大步数限制");

        // 记录达到最大步数
        agentMetrics.recordMaxStepsReached(maxSteps);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                "自动分析已达到最大步数，未能收敛到最终结论。",
                "MEDIUM",
                "达到最大步数限制",
                List.of("请人工查看已有 Observation", "必要时缩小问题范围后重新发起分析"),
                List.of("当前分析链路未完成"));
        AgentResult result = AgentResult.maxStepsReached(sessionId, degraded, history, maxSteps);
        emitSubAgentFinished(sessionId, maxSteps, context, null);
        agentAuditService.recordSessionResult(
                sessionId,
                customerId,
                doctorId,
                goal,
                result,
                maxSteps,
                System.currentTimeMillis() - startTime);
        return result;
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

        for (int step = startStep; step < maxSteps; step++) {
            log.info("Agent执行第{}步", step + 1);

            try {
                AgentSubAgent subAgent = selectSubAgent(goal, context);
                switchSubAgent(sessionId, step + 1, context, subAgent, eventSink);
                String systemPrompt = promptBuilder.buildSubAgentSystemPrompt(subAgent, toolRegistry.getAllTools());
                ParsedThought parsedThought = requestThoughtResultStream(
                        systemPrompt, messages, step, customerId, sessionId, eventSink, step + 1, context);
                log.info("Agent响应: {}", parsedThought.responseText());

                ThoughtResult thought = parsedThought.thoughtResult();
                history.add(Step.thought(thought.getThinking()));
                messages.add(Message.assistant(parsedThought.responseText()));
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.THOUGHT, thought.getThinking());

                if (thought.isFinished()) {
                    StructuredFinalAnswer finalAnswer = thought.getStructuredFinalAnswer();
                    SafetyAssessment finalAnswerAssessment = medicalSafetyService.assessFinalAnswer(
                            finalAnswer,
                            history);
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history, context);

                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    AgentResult result = AgentResult.success(
                            sessionId,
                            finalAnswerAssessment.isBlocked()
                                    ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                                    : finalAnswer,
                            history,
                            step + 1);
                    emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.FINAL_ANSWER,
                            result.getStructuredFinalAnswer());
                    emitSubAgentFinished(sessionId, step + 1, context, eventSink);
                    emitDone(eventSink, sessionId, step + 1, result.getStatus().name());
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                            duration);
                    return result;
                }

                ActionDecision action = thought.getAction();
                history.add(Step.action(action));
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.ACTION, actionToEventData(action));

                log.info("Agent执行行动: tool={}, input={}",
                        action.getToolName(),
                        JSON.toJSONString(action.getInput()));

                SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action, history);
                if (actionAssessment.isBlocked()) {
                    history.add(Step.observation(actionAssessment.message()));
                    messages.add(Message.user("Observation: " + actionAssessment.message()));
                    emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.OBSERVATION,
                            actionAssessment.message());
                    context.addEvidence(action.getToolName(), "SAFETY_RULE", actionAssessment.message(),
                            action.getInput());
                    emitContextUpdated(sessionId, step + 1, context, action.getToolName(), eventSink);
                    sessionService.updateSession(sessionId, history, messages, context, step + 1);
                    continue;
                }

                ActionResult actionResult = actionExecutor.execute(
                        action,
                        customerId,
                        doctorId,
                        sessionId);

                if (actionResult.isNeedsApproval()) {
                    log.info("行动需要医生确认，Agent暂停");
                    sessionService.pauseSession(sessionId, history, messages, context);
                    agentMetrics.recordPendingApproval(action.getToolName());

                    AgentResult result = AgentResult.pendingApproval(
                            sessionId,
                            actionResult.getTask(),
                            history,
                            step + 1);
                    emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.PENDING_APPROVAL,
                            actionResult.getTask());
                    emitSubAgentFinished(sessionId, step + 1, context, eventSink);
                    emitDone(eventSink, sessionId, step + 1, result.getStatus().name());
                    agentAuditService.recordSessionResult(
                            sessionId,
                            customerId,
                            doctorId,
                            goal,
                            result,
                            step + 1,
                            System.currentTimeMillis() - startTime);
                    return result;
                }

                String observation = formatObservation(actionResult);
                history.add(Step.observation(observation));
                messages.add(Message.user("Observation: " + observation));
                updateContextAfterAction(context, action, actionResult, observation);
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.OBSERVATION, observation);

                log.info("Agent观察结果: {}", observation);
                sessionService.updateSession(sessionId, history, messages, context, step + 1);
            } catch (Exception e) {
                log.error("Agent执行异常", e);
                sessionService.failSession(sessionId, e.getMessage());

                long duration = System.currentTimeMillis() - startTime;
                agentMetrics.recordExecutionFailure(e.getClass().getSimpleName(), step + 1, duration);

                StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                        buildExecutionDegradedSummary(e),
                        "MEDIUM",
                        buildExecutionRuntimeEvidence(e),
                        List.of("请人工接管当前会话", "补充近期关键数据后再尝试重新分析"),
                        List.of("当前未获得可用的自动分析结果"));
                AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, history, step + 1);
                emitEvent(eventSink, sessionId, step + 1, AgentStreamEventType.ERROR,
                        Map.of("message", e.getMessage(), "structuredFinalAnswer", degraded));
                emitSubAgentFinished(sessionId, step + 1, context, eventSink);
                emitDone(eventSink, sessionId, step + 1, result.getStatus().name());
                agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        duration);
                return result;
            }
        }

        log.warn("Agent达到最大步数限制");
        sessionService.failSession(sessionId, "达到最大步数限制");
        agentMetrics.recordMaxStepsReached(maxSteps, System.currentTimeMillis() - startTime);

        StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                "自动分析已达到最大步数，未能收敛到最终结论。",
                "MEDIUM",
                "达到最大步数限制",
                List.of("请人工查看已有 Observation", "必要时缩小问题范围后重新发起分析"),
                List.of("当前分析链路未完成"));
        AgentResult result = AgentResult.maxStepsReached(sessionId, degraded, history, maxSteps);
        emitEvent(eventSink, sessionId, maxSteps, AgentStreamEventType.FINAL_ANSWER, degraded);
        emitSubAgentFinished(sessionId, maxSteps, context, eventSink);
        emitDone(eventSink, sessionId, maxSteps, result.getStatus().name());
        agentAuditService.recordSessionResult(
                sessionId,
                customerId,
                doctorId,
                goal,
                result,
                maxSteps,
                System.currentTimeMillis() - startTime);
        return result;
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
        return baseSystemPrompt + """

                ## Current Structured Context
                %s
                """.formatted(context.toPromptSection());
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
        context.putSlot("last_tool_result", Map.of(
                "toolName", action.getToolName(),
                "input", action.getInput(),
                "observation", observation
        ), action.getToolName());
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
