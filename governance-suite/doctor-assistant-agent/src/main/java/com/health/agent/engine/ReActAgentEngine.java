package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.client.DeepSeekClient;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.*;
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
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history);

                    // 记录成功
                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    return AgentResult.success(sessionId, thought.getFinalAnswer(), history, step + 1);
                }

                // 4. 执行 Action
                ActionDecision action = thought.getAction();
                history.add(Step.action(action));

                log.info("Agent执行行动: tool={}, input={}",
                    action.getToolName(),
                    JSON.toJSONString(action.getInput())
                );

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

                    return AgentResult.pendingApproval(
                        sessionId,
                        actionResult.getTask(),
                        history,
                        step + 1
                    );
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

                return AgentResult.error(sessionId, e.getMessage(), history, step + 1);
            }
        }

        log.warn("Agent达到最大步数限制");
        sessionService.failSession(sessionId, "达到最大步数限制");

        // 记录达到最大步数
        agentMetrics.recordMaxStepsReached(maxSteps);

        return AgentResult.maxStepsReached(sessionId, history, maxSteps);
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
            return AgentResult.error(sessionId, e.getMessage(), new ArrayList<>(), 0);
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
                    log.info("Agent任务完成");
                    sessionService.completeSession(sessionId, history);

                    // 记录成功
                    long duration = System.currentTimeMillis() - startTime;
                    agentMetrics.recordExecutionSuccess(step + 1, duration);

                    return AgentResult.success(sessionId, thought.getFinalAnswer(), history, step + 1);
                }

                // 4. 执行 Action
                ActionDecision action = thought.getAction();
                history.add(Step.action(action));

                log.info("Agent执行行动: tool={}, input={}",
                    action.getToolName(),
                    JSON.toJSONString(action.getInput())
                );

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

                    return AgentResult.pendingApproval(
                        sessionId,
                        actionResult.getTask(),
                        history,
                        step + 1
                    );
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

                return AgentResult.error(sessionId, e.getMessage(), history, step + 1);
            }
        }

        log.warn("Agent达到最大步数限制");
        sessionService.failSession(sessionId, "达到最大步数限制");

        // 记录达到最大步数
        agentMetrics.recordMaxStepsReached(maxSteps);

        return AgentResult.maxStepsReached(sessionId, history, maxSteps);
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
                      "finalAnswer": "患者35岁女性，当前减重进展总体良好，但近期存在焦虑情绪和短期体重反弹。建议继续当前减重计划，关注心理状态，并持续监测血压。"
                    }
                    """;
        }
    }

    private record ParsedThought(String responseText, ThoughtResult thoughtResult) {
    }
}
