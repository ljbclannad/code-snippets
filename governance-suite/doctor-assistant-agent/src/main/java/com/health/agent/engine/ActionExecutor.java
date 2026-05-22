package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.ToolMetrics;
import com.health.agent.model.*;
import com.health.agent.service.PendingTaskService;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolInputValidator;
import com.health.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Map;

/**
 * 行动执行器
 * 根据工具风险等级决定执行策略
 */
@Slf4j
@Service
public class ActionExecutor {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private PendingTaskService pendingTaskService;

    @Autowired
    private ToolMetrics toolMetrics;

    @Autowired
    private ToolInputValidator toolInputValidator;

    @Value("${agent.tool-timeout-millis:3000}")
    private long toolTimeoutMillis;

    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(new ToolThreadFactory());

    /**
     * 执行 Agent 决定的行动
     */
    public ActionResult execute(ActionDecision action, String customerId, String doctorId, String sessionId) {
        Tool tool = toolRegistry.getTool(action.getToolName());

        if (tool == null) {
            return ActionResult.error("未知工具: " + action.getToolName());
        }

        log.info("执行工具: {}, 风险等级: {}", tool.getName(), tool.getRiskLevel());

        // 记录工具调用
        toolMetrics.recordToolCall(tool.getName(), tool.getRiskLevel());
        toolMetrics.recordToolCallByRiskLevel(tool.getRiskLevel());
        toolMetrics.recordToolPopularity(tool.getName());

        Map<String, Object> normalizedInput;
        try {
            normalizedInput = toolInputValidator.validateAndNormalize(tool, action.getInput());
        } catch (AgentException e) {
            toolMetrics.recordToolFailure(tool.getName(), "INVALID_TOOL_INPUT");
            return ActionResult.error(e.getMessage());
        }

        ActionDecision normalizedAction = ActionDecision.builder()
            .toolName(action.getToolName())
            .input(normalizedInput)
            .reason(action.getReason())
            .build();

        // 根据风险等级决定执行策略
        switch (tool.getRiskLevel()) {
            case LOW:
                // 低风险：直接执行
                return executeDirectly(tool, normalizedInput);

            case MEDIUM:
                // 中风险：执行但记录审计日志
                ActionResult result = executeDirectly(tool, normalizedInput);
                auditLog(customerId, normalizedAction, result);
                return result;

            case HIGH:
                // 高风险：创建待确认任务，暂停 Agent
                toolMetrics.recordToolNeedsApproval(tool.getName());
                return createPendingTask(customerId, doctorId, sessionId, tool, normalizedAction);

            default:
                return ActionResult.error("未知风险等级");
        }
    }

    public ActionResult executeApprovedAction(ActionDecision action) {
        Tool tool = toolRegistry.getTool(action.getToolName());
        if (tool == null) {
            return ActionResult.error("未知工具: " + action.getToolName());
        }

        Map<String, Object> normalizedInput = toolInputValidator.validateAndNormalize(tool, action.getInput());
        return executeDirectly(tool, normalizedInput);
    }

    /**
     * 直接执行工具
     */
    private ActionResult executeDirectly(Tool tool, Map<String, Object> input) {
        long startTime = System.currentTimeMillis();

        try {
            Object output = executeWithTimeout(tool, input);

            // 记录成功
            long duration = System.currentTimeMillis() - startTime;
            toolMetrics.recordToolSuccess(tool.getName(), duration);

            return ActionResult.success(output);
        } catch (Exception e) {
            log.error("工具执行失败: {}", tool.getName(), e);

            // 记录失败
            toolMetrics.recordToolFailure(tool.getName(), e.getClass().getSimpleName());

            return ActionResult.error(e.getMessage());
        }
    }

    private Object executeWithTimeout(Tool tool, Map<String, Object> input) throws Exception {
        Future<Object> future = toolExecutor.submit(new ToolExecutionCallable(tool, input));
        try {
            return future.get(toolTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentException("TIMEOUT: 工具 `%s` 执行超过 %dms".formatted(
                tool.getName(), toolTimeoutMillis), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new AgentException("工具执行失败: " + tool.getName(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("工具执行被中断: " + tool.getName(), e);
        }
    }

    /**
     * 创建待确认任务（高风险行动）
     */
    private ActionResult createPendingTask(
        String customerId,
        String doctorId,
        String sessionId,
        Tool tool,
        ActionDecision action
    ) {
        PendingTask task = PendingTask.builder()
            .agentSessionId(sessionId)
            .customerId(customerId)
            .doctorId(doctorId)
            .toolName(tool.getName())
            .toolDescription(tool.getDescription())
            .inputJson(JSON.toJSONString(action.getInput()))
            .reason(action.getReason())
            .status(TaskStatus.PENDING.name())
            .createdAt(LocalDateTime.now())
            .expireAt(LocalDateTime.now().plusHours(24))
            .build();

        pendingTaskService.save(task);

        log.info("创建待确认任务: taskId={}, tool={}, customerId={}", task.getId(), tool.getName(), customerId);

        return ActionResult.pendingApproval(task);
    }

    /**
     * 审计日志
     */
    private void auditLog(String customerId, ActionDecision action, ActionResult result) {
        log.info("审计日志: customerId={}, action={}, success={}",
            customerId, action.getToolName(), result.isSuccess());
        // TODO: 记录到审计日志表
    }

    @PreDestroy
    void shutdownExecutor() {
        toolExecutor.shutdownNow();
    }

    private record ToolExecutionCallable(Tool tool, Map<String, Object> input) implements Callable<Object> {
        @Override
        public Object call() {
            return tool.getExecutor().execute(input);
        }
    }

    private static class ToolThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-tool-executor");
            thread.setDaemon(true);
            return thread;
        }
    }
}
