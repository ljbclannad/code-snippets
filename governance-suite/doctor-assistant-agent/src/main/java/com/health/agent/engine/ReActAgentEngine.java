package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.client.DeepSeekClient;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.*;
import com.health.agent.safety.MedicalSafetyService;
import com.health.agent.safety.SafetyAssessment;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.AgentSessionService;
import com.health.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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

        SafetyAssessment goalAssessment = medicalSafetyService.assessGoal(goal);
        if (goalAssessment.isBlocked()) {
            sessionService.completeSession(sessionId, history);
            AgentResult result = AgentResult.success(
                sessionId,
                medicalSafetyService.buildSafetyStructuredAnswer(goalAssessment.message()),
                history,
                0
            );
            agentAuditService.recordSessionResult(sessionId, customerId, doctorId, goal, result, 0, System.currentTimeMillis() - startTime);
            return result;
        }

        // 构建 System Prompt
        String systemPrompt = promptBuilder.buildSystemPrompt(toolRegistry.getAllTools());

        // 构建初始 User Prompt
        String userPrompt = promptBuilder.buildUserPrompt(goal, customerId);
        messages.add(Message.user(userPrompt));

        for (int step = 0; step < maxSteps; step++) {
            log.info("Agent执行第{}步", step + 1);

            try {
                // 1. 调用 DeepSeek API 获取 Thought 和 Action
                ParsedThought parsedThought = requestThoughtResult(systemPrompt, messages, step, customerId);
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
                        history
                    );
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history);

                    // 记录成功
                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    AgentResult result = AgentResult.success(
                        sessionId,
                        finalAnswerAssessment.isBlocked()
                            ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                            : finalAnswer,
                        history,
                        step + 1
                    );
                    agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        duration
                    );
                    return result;
                }

                // 4. 执行 Action
                ActionDecision action = thought.getAction();
                history.add(Step.action(action));

                log.info("Agent执行行动: tool={}, input={}",
                    action.getToolName(),
                    JSON.toJSONString(action.getInput())
                );

                SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action, history);
                if (actionAssessment.isBlocked()) {
                    history.add(Step.observation(actionAssessment.message()));
                    messages.add(Message.user("Observation: " + actionAssessment.message()));
                    sessionService.updateSession(sessionId, history, messages, step + 1);
                    continue;
                }

                ActionResult actionResult = actionExecutor.execute(
                    action,
                    customerId,
                    doctorId,
                    sessionId
                );

                // 5. 检查是否需要人工确认
                if (actionResult.isNeedsApproval()) {
                    log.info("行动需要医生确认，Agent暂停");
                    sessionService.pauseSession(sessionId, history, messages);

                    // 记录等待审批
                    agentMetrics.recordPendingApproval(action.getToolName());

                    AgentResult result = AgentResult.pendingApproval(
                        sessionId,
                        actionResult.getTask(),
                        history,
                        step + 1
                    );
                    agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        System.currentTimeMillis() - startTime
                    );
                    return result;
                }

                // 6. 记录 Observation
                String observation = formatObservation(actionResult);
                history.add(Step.observation(observation));
                messages.add(Message.user("Observation: " + observation));

                log.info("Agent观察结果: {}", observation);

                // 7. 更新会话状态
                sessionService.updateSession(sessionId, history, messages, step + 1);

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
                    List.of("当前未获得可用的自动分析结果")
                );
                AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, history, step + 1);
                agentAuditService.recordSessionResult(
                    sessionId,
                    customerId,
                    doctorId,
                    goal,
                    result,
                    step + 1,
                    System.currentTimeMillis() - startTime
                );
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
            List.of("当前分析链路未完成")
        );
        AgentResult result = AgentResult.maxStepsReached(sessionId, degraded, history, maxSteps);
        agentAuditService.recordSessionResult(
            sessionId,
            customerId,
            doctorId,
            goal,
            result,
            maxSteps,
            System.currentTimeMillis() - startTime
        );
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
                currentStep + 1
            );

        } catch (Exception e) {
            log.error("Agent恢复执行异常", e);
            sessionService.failSession(sessionId, e.getMessage());
            StructuredFinalAnswer degraded = medicalSafetyService.buildDegradedStructuredAnswer(
                "恢复执行失败，当前会话无法继续自动推进。",
                "HIGH",
                "恢复执行失败",
                List.of("请人工复核当前待审批任务和会话状态", "必要时重新发起新的减重分析会话"),
                List.of("自动恢复链路已中断", e.getMessage())
            );
            return AgentResult.error(sessionId, e.getMessage(), degraded, new ArrayList<>(), 0);
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
        int startStep
    ) {
        long startTime = System.currentTimeMillis();
        log.info("继续ReAct循环: sessionId={}, startStep={}", sessionId, startStep);

        // 构建 System Prompt
        String systemPrompt = promptBuilder.buildSystemPrompt(toolRegistry.getAllTools());

        for (int step = startStep; step < maxSteps; step++) {
            log.info("Agent执行第{}步", step + 1);

            try {
                // 1. 调用 DeepSeek API 获取 Thought 和 Action
                ParsedThought parsedThought = requestThoughtResult(systemPrompt, messages, step, customerId);
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
                        history
                    );
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history);

                    // 记录成功
                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    AgentResult result = AgentResult.success(
                        sessionId,
                        finalAnswerAssessment.isBlocked()
                            ? medicalSafetyService.buildSafetyStructuredAnswer(finalAnswerAssessment.message())
                            : finalAnswer,
                        history,
                        step + 1
                    );
                    agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        duration
                    );
                    return result;
                }

                // 4. 执行 Action
                ActionDecision action = thought.getAction();
                history.add(Step.action(action));

                log.info("Agent执行行动: tool={}, input={}",
                    action.getToolName(),
                    JSON.toJSONString(action.getInput())
                );

                SafetyAssessment actionAssessment = medicalSafetyService.assessAction(action, history);
                if (actionAssessment.isBlocked()) {
                    history.add(Step.observation(actionAssessment.message()));
                    messages.add(Message.user("Observation: " + actionAssessment.message()));
                    sessionService.updateSession(sessionId, history, messages, step + 1);
                    continue;
                }

                ActionResult actionResult = actionExecutor.execute(
                    action,
                    customerId,
                    doctorId,
                    sessionId
                );

                // 5. 检查是否需要人工确认
                if (actionResult.isNeedsApproval()) {
                    log.info("行动需要医生确认，Agent暂停");
                    sessionService.pauseSession(sessionId, history, messages);

                    // 记录等待审批
                    agentMetrics.recordPendingApproval(action.getToolName());

                    AgentResult result = AgentResult.pendingApproval(
                        sessionId,
                        actionResult.getTask(),
                        history,
                        step + 1
                    );
                    agentAuditService.recordSessionResult(
                        sessionId,
                        customerId,
                        doctorId,
                        goal,
                        result,
                        step + 1,
                        System.currentTimeMillis() - startTime
                    );
                    return result;
                }

                // 6. 记录 Observation
                String observation = formatObservation(actionResult);
                history.add(Step.observation(observation));
                messages.add(Message.user("Observation: " + observation));

                log.info("Agent观察结果: {}", observation);

                // 7. 更新会话状态
                sessionService.updateSession(sessionId, history, messages, step + 1);

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
                    List.of("当前未获得可用的自动分析结果")
                );
                AgentResult result = AgentResult.error(sessionId, e.getMessage(), degraded, history, step + 1);
                agentAuditService.recordSessionResult(
                    sessionId,
                    customerId,
                    doctorId,
                    goal,
                    result,
                    step + 1,
                    System.currentTimeMillis() - startTime
                );
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
            List.of("当前分析链路未完成")
        );
        AgentResult result = AgentResult.maxStepsReached(sessionId, degraded, history, maxSteps);
        agentAuditService.recordSessionResult(
            sessionId,
            customerId,
            doctorId,
            goal,
            result,
            maxSteps,
            System.currentTimeMillis() - startTime
        );
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
        String customerId
    ) {
        if (useMock) {
            String mockResponse = mockDeepSeekResponse(step, customerId);
            return new ParsedThought(mockResponse, responseParser.parse(mockResponse));
        }

        String responseText = deepSeekClient.chat(systemPrompt, messages).getContent();
        try {
            return new ParsedThought(responseText, responseParser.parse(responseText));
        } catch (AgentException parseException) {
            log.warn("Agent响应协议不合法，准备纠偏重试: {}", parseException.getMessage());
            List<Message> repairMessages = new ArrayList<>(messages);
            repairMessages.add(Message.assistant(responseText));
            repairMessages.add(Message.user(promptBuilder.buildProtocolRepairPrompt(parseException.getMessage())));

            AgentException lastException = parseException;
            for (int attempt = 0; attempt < protocolRepairAttempts; attempt++) {
                String repairedResponse = deepSeekClient.chat(systemPrompt, repairMessages).getContent();
                try {
                    return new ParsedThought(repairedResponse, responseParser.parse(repairedResponse));
                } catch (AgentException repairException) {
                    lastException = repairException;
                    repairMessages.add(Message.assistant(repairedResponse));
                    repairMessages.add(Message.user(promptBuilder.buildProtocolRepairPrompt(repairException.getMessage())));
                }
            }
            throw lastException;
        }
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
