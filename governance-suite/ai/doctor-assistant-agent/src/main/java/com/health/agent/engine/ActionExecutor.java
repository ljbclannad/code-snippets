package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.ToolMetrics;
import com.health.agent.model.*;
import com.health.agent.service.AgentAuditService;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
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
    private AgentAuditService agentAuditService;

    @Autowired
    private ToolMetrics toolMetrics;

    @Autowired
    private ToolInputValidator toolInputValidator;

    @Value("${agent.tool-timeout-millis:3000}")
    private long toolTimeoutMillis;

    @Value("${agent.tool-executor.core-pool-size:8}")
    private int toolExecutorCorePoolSize;

    @Value("${agent.tool-executor.max-pool-size:16}")
    private int toolExecutorMaxPoolSize;

    @Value("${agent.tool-executor.queue-capacity:64}")
    private int toolExecutorQueueCapacity;

    @Value("${agent.tool-executor.keep-alive-millis:60000}")
    private long toolExecutorKeepAliveMillis;

    private volatile ThreadPoolExecutor toolExecutor;

    /**
     * 执行 Agent 决定的行动
     */
    public ActionResult execute(ActionDecision action, String customerId, String doctorId, String sessionId) {
        // 先把 toolName 映射到真实工具定义，避免模型输出了不存在的工具还继续往下执行。
        Tool tool = toolRegistry.getTool(action.getToolName());

        if (tool == null) {
            return ActionResult.error("未知工具: " + action.getToolName());
        }

        log.info("执行工具: {}, 风险等级: {}", tool.getName(), tool.getRiskLevel());

        // 统一记录调用指标，目的是后续能按工具和风险等级观察热点、失败率和使用分布。
        toolMetrics.recordToolCall(tool.getName(), tool.getRiskLevel());
        toolMetrics.recordToolCallByRiskLevel(tool.getRiskLevel());
        toolMetrics.recordToolPopularity(tool.getName());

        Map<String, Object> normalizedInput;
        try {
            // 在真正执行前做 schema 级输入校验和归一化，目的是把模型生成的参数收敛成可控格式，
            // 同时尽早拦住缺字段、类型错误、越界值这类问题，避免把脏数据传给具体工具实现。
            normalizedInput = toolInputValidator.validateAndNormalize(tool, action.getInput());
        } catch (AgentException e) {
            // 输入非法也要走失败指标和审计，目的是让“模型选对了工具但参数不合法”这类问题可观测、可追踪。
            toolMetrics.recordToolFailure(tool.getName(), "INVALID_TOOL_INPUT");
            agentAuditService.recordToolExecution(
                sessionId,
                customerId,
                doctorId,
                null,
                tool.getName(),
                JSON.toJSONString(action.getInput()),
                null,
                tool.getRiskLevel().name(),
                "INVALID_TOOL_INPUT"
            );
            return ActionResult.error(e.getMessage());
        }

        // 后续一律使用归一化后的 Action，目的是确保审计、审批和实际执行看到的是同一份标准化输入。
        ActionDecision normalizedAction = ActionDecision.builder()
            .toolName(action.getToolName())
            .input(normalizedInput)
            .reason(action.getReason())
            .build();

        // 按风险等级分流执行策略，目的是把“能直接执行”和“必须人工介入”的路径在这里明确切开，
        // 避免高风险动作绕过审批链，也避免低风险动作被不必要地阻塞。
        switch (tool.getRiskLevel()) {
            case LOW:
                // 低风险工具直接执行，目标是减少医生端操作负担，让只读查询和低风险分析快速返回。
                return executeDirectly(tool, normalizedInput, sessionId, customerId, doctorId, null);

            case MEDIUM:
                // 中风险工具允许执行，但仍要留下完整审计，目的是既保持效率，又保留事后复盘能力。
                ActionResult result = executeDirectly(tool, normalizedInput, sessionId, customerId, doctorId, null);
                return result;

            case HIGH:
                // 高风险工具不直接执行，而是转成待审批任务，目的是把减重方案调整和医学复核
                // 这类高影响动作显式交还给医生确认。
                toolMetrics.recordToolNeedsApproval(tool.getName());
                return createPendingTask(customerId, doctorId, sessionId, tool, normalizedAction);

            default:
                // 防御性分支，目的是在风险枚举出现异常值时安全失败，而不是落到未定义行为。
                return ActionResult.error("未知风险等级");
        }
    }

    public ActionResult executeApprovedAction(ActionDecision action) {
        Tool tool = toolRegistry.getTool(action.getToolName());
        if (tool == null) {
            return ActionResult.error("未知工具: " + action.getToolName());
        }

        Map<String, Object> normalizedInput = toolInputValidator.validateAndNormalize(tool, action.getInput());
        return executeDirectly(tool, normalizedInput, null, null, null, null);
    }

    /**
     * 直接执行工具
     */
    private ActionResult executeDirectly(
        Tool tool,
        Map<String, Object> input,
        String sessionId,
        String customerId,
        String doctorId,
        String goal
    ) {
        long startTime = System.currentTimeMillis();

        try {
            Object rawOutput = executeWithTimeout(tool, input);
            ToolExecutionOutput output = standardizeToolOutput(tool, rawOutput);

            // 记录成功
            long duration = System.currentTimeMillis() - startTime;
            toolMetrics.recordToolSuccess(tool.getName(), duration);
            agentAuditService.recordToolExecution(
                sessionId,
                customerId,
                doctorId,
                goal,
                tool.getName(),
                JSON.toJSONString(input),
                JSON.toJSONString(output),
                tool.getRiskLevel().name(),
                "SUCCESS"
            );

            return ActionResult.success(output);
        } catch (Exception e) {
            log.error("工具执行失败: {}", tool.getName(), e);

            // 记录失败
            long duration = System.currentTimeMillis() - startTime;
            toolMetrics.recordToolFailure(tool.getName(), e.getClass().getSimpleName(), duration);
            agentAuditService.recordToolExecution(
                sessionId,
                customerId,
                doctorId,
                goal,
                tool.getName(),
                JSON.toJSONString(input),
                null,
                tool.getRiskLevel().name(),
                "ERROR"
            );

            return ActionResult.error(e.getMessage());
        }
    }

    private Object executeWithTimeout(Tool tool, Map<String, Object> input) throws Exception {
        Future<Object> future = getToolExecutor().submit(new ToolExecutionCallable(tool, input));
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

    private ToolExecutionOutput standardizeToolOutput(Tool tool, Object rawOutput) {
        if (rawOutput instanceof ToolExecutionOutput standardized) {
            return standardized;
        }

        Map<String, Object> stateUpdates = new java.util.LinkedHashMap<>();
        String slotKey = resolveContextSlot(tool.getName());
        stateUpdates.put(slotKey, rawOutput);

        return ToolExecutionOutput.builder()
            .data(rawOutput)
            .summary(tool.getDescription())
            .stateUpdates(stateUpdates)
            .evidence(List.of(
                ToolEvidence.builder()
                    .source("TOOL_OUTPUT")
                    .summary(tool.getDescription())
                    .payload(rawOutput)
                    .build()
            ))
            .build();
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

    private ThreadPoolExecutor getToolExecutor() {
        if (toolExecutor == null) {
            synchronized (this) {
                if (toolExecutor == null) {
                    toolExecutor = createToolExecutor();
                }
            }
        }
        return toolExecutor;
    }

    private ThreadPoolExecutor createToolExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            toolExecutorCorePoolSize,
            toolExecutorMaxPoolSize,
            toolExecutorKeepAliveMillis,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(toolExecutorQueueCapacity),
            new ToolThreadFactory(),
            (runnable, threadPoolExecutor) -> {
                toolMetrics.recordToolExecutorRejected("agent-tool-executor");
                throw new RejectedExecutionException("工具执行线程池已饱和");
            }
        );
        executor.allowCoreThreadTimeOut(true);
        toolMetrics.bindToolExecutor("agent-tool-executor", executor);
        return executor;
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
        agentAuditService.recordPendingTaskCreated(task, null);

        log.info("创建待确认任务: taskId={}, tool={}, customerId={}", task.getId(), tool.getName(), customerId);

        return ActionResult.pendingApproval(task);
    }

    @PreDestroy
    void shutdownExecutor() {
        if (toolExecutor != null) {
            toolExecutor.shutdownNow();
        }
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
