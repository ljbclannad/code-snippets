package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.ToolMetrics;
import com.health.agent.model.ActionDecision;
import com.health.agent.model.ActionResult;
import com.health.agent.model.PendingTask;
import com.health.agent.model.TaskStatus;
import com.health.agent.model.ToolEvidence;
import com.health.agent.service.AgentAuditService;
import com.health.agent.service.PendingTaskService;
import com.health.agent.service.PlatformToolOutputAdapter;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolInputValidator;
import com.health.agent.tool.ToolRegistry;
import com.health.agent.tool.ToolRiskLevel;
import com.health.agent.tool.ToolAccessPolicy;
import com.lx.governance.agentplatform.model.AgentAccessContext;
import com.lx.governance.agentplatform.model.ToolDefinition;
import com.lx.governance.agentplatform.model.ToolIdempotencyPolicy;
import com.lx.governance.agentplatform.model.PlatformToolOutput;
import com.lx.governance.agentplatform.model.ToolRetryPolicy;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 行动执行器
 * 根据工具风险等级和平台策略决定执行方式。
 */
@Slf4j
@Service
public class ActionExecutor {

    private static final String TOOL_EXECUTOR_NAME = "agent-tool-executor";

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

    @Autowired
    private PlatformToolOutputAdapter platformToolOutputAdapter;

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
    private final ConcurrentMap<String, RateLimitWindow> rateLimitWindows = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CircuitBreakerState> circuitBreakerStates = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PlatformToolOutput> idempotentOutputs = new ConcurrentHashMap<>();

    public ActionResult execute(ActionDecision action, String customerId, String doctorId, String sessionId) {
        return execute(action, customerId, doctorId, sessionId, null);
    }

    /**
     * 执行 Agent 决定的行动。
     */
    public ActionResult execute(
        ActionDecision action,
        String customerId,
        String doctorId,
        String sessionId,
        AgentAccessContext accessContext
    ) {
        Tool tool = toolRegistry.getTool(action.getToolName());
        ToolDefinition definition = toolRegistry.getDefinition(action.getToolName());

        if (tool == null) {
            return ActionResult.error("UNKNOWN_TOOL", "未知工具: " + action.getToolName());
        }

        ToolRiskLevel effectiveRiskLevel = resolveRiskLevel(tool, definition);
        if (!ToolAccessPolicy.allows(definition, accessContext)) {
            return auditAndReject(
                sessionId,
                customerId,
                doctorId,
                tool,
                definition,
                action.getInput(),
                "TOOL_ACCESS_DENIED",
                "工具无权限: " + tool.getName(),
                effectiveRiskLevel
            );
        }

        log.info("执行工具: {}, 风险等级: {}", tool.getName(), effectiveRiskLevel);
        toolMetrics.recordToolCall(tool.getName(), effectiveRiskLevel);
        toolMetrics.recordToolCallByRiskLevel(effectiveRiskLevel);
        toolMetrics.recordToolPopularity(tool.getName());

        Map<String, Object> normalizedInput;
        try {
            normalizedInput = toolInputValidator.validateAndNormalize(tool, action.getInput());
        } catch (AgentException e) {
            toolMetrics.recordToolFailure(tool.getName(), "INVALID_TOOL_INPUT");
            if (shouldAudit(definition)) {
                agentAuditService.recordToolExecution(
                    sessionId,
                    customerId,
                    doctorId,
                    null,
                    tool.getName(),
                    JSON.toJSONString(action.getInput()),
                    null,
                    effectiveRiskLevel.name(),
                    "INVALID_TOOL_INPUT"
                );
            }
            return buildErrorResult(e);
        }

        ActionDecision normalizedAction = ActionDecision.builder()
            .toolName(action.getToolName())
            .input(normalizedInput)
            .reason(action.getReason())
            .build();

        PlatformToolOutput cachedOutput = resolveIdempotentReplay(tool, definition, normalizedInput);
        if (cachedOutput != null) {
            if (shouldAudit(definition)) {
                agentAuditService.recordToolExecution(
                    sessionId,
                    customerId,
                    doctorId,
                    null,
                    tool.getName(),
                    JSON.toJSONString(normalizedInput),
                    JSON.toJSONString(cachedOutput),
                    effectiveRiskLevel.name(),
                    "IDEMPOTENT_REPLAY"
                );
            }
            return ActionResult.success(cachedOutput);
        }

        switch (effectiveRiskLevel) {
            case LOW:
                return executeDirectly(tool, definition, normalizedInput, sessionId, customerId, doctorId, null,
                    effectiveRiskLevel);
            case MEDIUM:
                return executeDirectly(tool, definition, normalizedInput, sessionId, customerId, doctorId, null,
                    effectiveRiskLevel);
            case HIGH:
                toolMetrics.recordToolNeedsApproval(tool.getName());
                return createPendingTask(customerId, doctorId, sessionId, tool, normalizedAction);
            default:
                return ActionResult.error("UNKNOWN_RISK_LEVEL", "未知风险等级");
        }
    }

    public ActionResult executeApprovedAction(ActionDecision action) {
        Tool tool = toolRegistry.getTool(action.getToolName());
        if (tool == null) {
            return ActionResult.error("UNKNOWN_TOOL", "未知工具: " + action.getToolName());
        }

        ToolDefinition definition = toolRegistry.getDefinition(action.getToolName());
        Map<String, Object> normalizedInput = toolInputValidator.validateAndNormalize(tool, action.getInput());
        return executeDirectly(tool, definition, normalizedInput, null, null, null, null,
            resolveRiskLevel(tool, definition));
    }

    private ActionResult executeDirectly(
        Tool tool,
        ToolDefinition definition,
        Map<String, Object> input,
        String sessionId,
        String customerId,
        String doctorId,
        String goal,
        ToolRiskLevel effectiveRiskLevel
    ) {
        long startTime = System.currentTimeMillis();

        try {
            Object rawOutput = executeWithPolicy(tool, definition, input);
            PlatformToolOutput output = standardizeToolOutput(tool, rawOutput);
            rememberIdempotentOutput(tool, definition, input, output);
            resetCircuitBreaker(tool.getName());

            long duration = System.currentTimeMillis() - startTime;
            toolMetrics.recordToolSuccess(tool.getName(), duration);
            if (shouldAudit(definition)) {
                agentAuditService.recordToolExecution(
                    sessionId,
                    customerId,
                    doctorId,
                    goal,
                    tool.getName(),
                    JSON.toJSONString(input),
                    JSON.toJSONString(output),
                    effectiveRiskLevel.name(),
                    "SUCCESS"
                );
            }
            return ActionResult.success(output);
        } catch (Exception e) {
            log.error("工具执行失败: {}", tool.getName(), e);
            recordToolFailure(tool.getName(), definition);

            long duration = System.currentTimeMillis() - startTime;
            toolMetrics.recordToolFailure(tool.getName(), e.getClass().getSimpleName(), duration);
            if (shouldAudit(definition)) {
                agentAuditService.recordToolExecution(
                    sessionId,
                    customerId,
                    doctorId,
                    goal,
                    tool.getName(),
                    JSON.toJSONString(input),
                    null,
                    effectiveRiskLevel.name(),
                    "ERROR"
                );
            }
            return buildErrorResult(e);
        }
    }

    private Object executeWithPolicy(Tool tool, ToolDefinition definition, Map<String, Object> input) throws Exception {
        enforceRateLimit(tool.getName(), definition);
        ensureCircuitClosed(tool.getName(), definition);

        ToolRetryPolicy retryPolicy = definition != null && definition.getRetryPolicy() != null
            ? definition.getRetryPolicy()
            : ToolRetryPolicy.noRetry();
        int maxAttempts = Math.max(1, retryPolicy.getMaxAttempts());
        long timeoutMillis = resolveTimeoutMillis(definition);

        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return executeWithTimeout(tool, input, timeoutMillis);
            } catch (Exception e) {
                lastError = e;
                if (!shouldRetry(e, retryPolicy, attempt, maxAttempts)) {
                    throw e;
                }
                sleepBeforeRetry(retryPolicy.getBackoffMs());
            }
        }

        throw lastError != null ? lastError : new AgentException("工具执行失败: " + tool.getName());
    }

    private Object executeWithTimeout(Tool tool, Map<String, Object> input, long timeoutMillis) throws Exception {
        Future<Object> future = getToolExecutor().submit(new ToolExecutionCallable(tool, input));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentException("TIMEOUT: 工具 `%s` 执行超过 %dms".formatted(tool.getName(), timeoutMillis), e);
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

    private ActionResult auditAndReject(
        String sessionId,
        String customerId,
        String doctorId,
        Tool tool,
        ToolDefinition definition,
        Map<String, Object> input,
        String resultStatus,
        String message,
        ToolRiskLevel effectiveRiskLevel
    ) {
        toolMetrics.recordToolFailure(tool.getName(), resultStatus);
        if (shouldAudit(definition)) {
            agentAuditService.recordToolExecution(
                sessionId,
                customerId,
                doctorId,
                null,
                tool.getName(),
                JSON.toJSONString(input),
                null,
                effectiveRiskLevel.name(),
                resultStatus
            );
        }
        return ActionResult.error(resultStatus, message);
    }

    private PlatformToolOutput resolveIdempotentReplay(
        Tool tool,
        ToolDefinition definition,
        Map<String, Object> normalizedInput
    ) {
        if (definition == null || definition.getIdempotencyPolicy() != ToolIdempotencyPolicy.IDEMPOTENT) {
            return null;
        }
        return idempotentOutputs.get(idempotencyKey(tool.getName(), normalizedInput));
    }

    private void rememberIdempotentOutput(
        Tool tool,
        ToolDefinition definition,
        Map<String, Object> normalizedInput,
        PlatformToolOutput output
    ) {
        if (definition == null || definition.getIdempotencyPolicy() != ToolIdempotencyPolicy.IDEMPOTENT) {
            return;
        }
        idempotentOutputs.put(idempotencyKey(tool.getName(), normalizedInput), output);
    }

    private String idempotencyKey(String toolName, Map<String, Object> normalizedInput) {
        return toolName + "::" + JSON.toJSONString(normalizedInput);
    }

    private void enforceRateLimit(String toolName, ToolDefinition definition) {
        int qpsLimit = definition != null ? definition.getQpsLimit() : 0;
        if (qpsLimit <= 0) {
            return;
        }

        RateLimitWindow window = rateLimitWindows.computeIfAbsent(toolName, ignored -> new RateLimitWindow());
        if (!window.tryAcquire(qpsLimit)) {
            throw new AgentException("TOOL_THROTTLED: 工具 `%s` 已达到限流阈值".formatted(toolName));
        }
    }

    private void ensureCircuitClosed(String toolName, ToolDefinition definition) {
        if (definition == null || definition.getCircuitBreakerFailureThreshold() <= 0
            || definition.getCircuitOpenMillis() <= 0) {
            return;
        }

        CircuitBreakerState state = circuitBreakerStates.computeIfAbsent(toolName, ignored -> new CircuitBreakerState());
        if (state.isOpen()) {
            throw new AgentException("TOOL_CIRCUIT_OPEN: 工具 `%s` 当前已熔断".formatted(toolName));
        }
    }

    private void recordToolFailure(String toolName, ToolDefinition definition) {
        if (definition == null || definition.getCircuitBreakerFailureThreshold() <= 0
            || definition.getCircuitOpenMillis() <= 0) {
            return;
        }

        CircuitBreakerState state = circuitBreakerStates.computeIfAbsent(toolName, ignored -> new CircuitBreakerState());
        state.recordFailure(definition.getCircuitBreakerFailureThreshold(), definition.getCircuitOpenMillis());
    }

    private void resetCircuitBreaker(String toolName) {
        CircuitBreakerState state = circuitBreakerStates.get(toolName);
        if (state != null) {
            state.reset();
        }
    }

    private long resolveTimeoutMillis(ToolDefinition definition) {
        long timeoutMillis = definition != null ? definition.getTimeoutMs() : 0L;
        return timeoutMillis > 0 ? timeoutMillis : toolTimeoutMillis;
    }

    private boolean shouldRetry(Exception e, ToolRetryPolicy retryPolicy, int attempt, int maxAttempts) {
        if (retryPolicy == null || attempt >= maxAttempts) {
            return false;
        }
        if (e instanceof AgentException agentException) {
            String message = agentException.getMessage();
            if (message != null && message.startsWith("TIMEOUT")) {
                return retryPolicy.isRetryOnTimeout();
            }
            if (message != null && message.startsWith("TOOL_THROTTLED")) {
                return retryPolicy.isRetryOnThrottled();
            }
        }
        return false;
    }

    private void sleepBeforeRetry(long backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("工具重试等待被中断", e);
        }
    }

    private boolean shouldAudit(ToolDefinition definition) {
        return definition == null || definition.isAuditEnabled();
    }

    private ActionResult buildErrorResult(Exception exception) {
        String message = exception != null && exception.getMessage() != null
                ? exception.getMessage()
                : "unknown";
        return ActionResult.error(resolveErrorCode(message), message);
    }

    private String resolveErrorCode(String message) {
        if (message == null || message.isBlank()) {
            return "TOOL_EXECUTION_ERROR";
        }
        int separator = message.indexOf(':');
        if (separator > 0) {
            return message.substring(0, separator).trim();
        }
        return "TOOL_EXECUTION_ERROR";
    }

    private ToolRiskLevel resolveRiskLevel(Tool tool, ToolDefinition definition) {
        if (definition == null || definition.getRiskLevel() == null || definition.getRiskLevel().isBlank()) {
            return tool.getRiskLevel();
        }
        try {
            return ToolRiskLevel.valueOf(definition.getRiskLevel());
        } catch (IllegalArgumentException ignore) {
            return tool.getRiskLevel();
        }
    }

    private PlatformToolOutput standardizeToolOutput(Tool tool, Object rawOutput) {
        return platformToolOutputAdapter.toPlatformOutput(
            tool.getName(),
            tool.getDescription(),
            rawOutput,
            resolveContextSlot(tool.getName())
        );
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
            case "triage_red_flags" -> "red_flag_triage";
            case "analyze_medication_adherence" -> "medication_adherence_analysis";
            case "summarize_symptom_timeline" -> "symptom_timeline";
            case "assess_follow_up_urgency" -> "follow_up_urgency_assessment";
            case "generate_patient_education_points" -> "patient_education_points";
            case "check_self_monitoring_completeness" -> "self_monitoring_completeness";
            case "extract_structured_symptoms" -> "structured_symptoms";
            case "screen_mental_health_risk" -> "mental_health_risk_screen";
            case "recommend_follow_up_data_requests" -> "follow_up_data_requests";
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
                toolMetrics.recordToolExecutorRejected(TOOL_EXECUTOR_NAME);
                throw new RejectedExecutionException("工具执行线程池已饱和");
            }
        );
        executor.allowCoreThreadTimeOut(true);
        toolMetrics.bindToolExecutor(TOOL_EXECUTOR_NAME, executor);
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

    private static final class RateLimitWindow {
        private long windowEpochSecond = -1L;
        private int count = 0;

        synchronized boolean tryAcquire(int limit) {
            long currentSecond = System.currentTimeMillis() / 1000L;
            if (windowEpochSecond != currentSecond) {
                windowEpochSecond = currentSecond;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }

    private static final class CircuitBreakerState {
        private int consecutiveFailures = 0;
        private long openUntilEpochMillis = 0L;

        synchronized boolean isOpen() {
            return openUntilEpochMillis > System.currentTimeMillis();
        }

        synchronized void recordFailure(int threshold, long openMillis) {
            consecutiveFailures++;
            if (consecutiveFailures >= threshold) {
                openUntilEpochMillis = System.currentTimeMillis() + openMillis;
                consecutiveFailures = 0;
            }
        }

        synchronized void reset() {
            consecutiveFailures = 0;
            openUntilEpochMillis = 0L;
        }
    }

    private static class ToolThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, TOOL_EXECUTOR_NAME);
            thread.setDaemon(true);
            return thread;
        }
    }
}
