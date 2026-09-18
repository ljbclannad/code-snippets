package com.lx.governance.aichat.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.stream.StreamResponseAccumulator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/** 用户链路只构造有限快照；回放查询及上报在独立有界线程池运行。 */
@Service
public class ChatEvaluationCollector {
    private final EvaluationCaptureProperties properties;
    private final ObjectMapper mapper;
    private final EvaluationCaptureTransport transport;
    private final MeterRegistry metrics;
    private final Executor executor;

    @Autowired
    public ChatEvaluationCollector(EvaluationCaptureProperties properties, ObjectMapper mapper,
                                   EvaluationCaptureTransport transport, MeterRegistry metrics) {
        this(properties, mapper, transport, metrics, new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.getQueueCapacity())), task -> {
                    Thread thread = new Thread(task, "evaluation-capture");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()));
    }

    ChatEvaluationCollector(EvaluationCaptureProperties properties, ObjectMapper mapper,
                            EvaluationCaptureTransport transport, MeterRegistry metrics, Executor executor) {
        this.properties = properties;
        this.mapper = mapper;
        this.transport = transport;
        this.metrics = metrics;
        this.executor = executor;
        if (properties.isEnabled()) {
            if (properties.getHashKey().length() < 16 || properties.getTimeoutMs() < 100
                    || properties.getTimeoutMs() > 10000 || properties.getMaxAttempts() < 1
                    || properties.getMaxAttempts() > 3 || properties.getMaxTextLength() < 100
                    || properties.getMaxPayloadBytes() < 1024 || properties.getMaxPayloadBytes() > 1048576
                    || properties.getQueueCapacity() < 1 || properties.getQueueCapacity() > 1024) {
                throw new IllegalArgumentException("评测采集配置无效：需要至少16字符摘要密钥，并配置有限超时、重试、队列及载荷大小");
            }
            validateBase(properties.getBaseUrl());
            validateBase(properties.getAgentBaseUrl());
        }
    }

    public void begin(ChatRequest request) {
        try {
            request.setEvaluationCaptureContext(null);
            if (!properties.isEnabled() || request.getEvaluation() == null) { return; }
            EvaluationBinding binding = request.getEvaluation();
            if (!validId(binding.batchId()) || !validId(binding.caseId()) || binding.trial() < 1 || binding.trial() > 10) {
                count("invalid_binding"); return;
            }
            CaptureSanitizer sanitizer = new CaptureSanitizer(mapper, properties, request);
            ObjectNode input = mapper.createObjectNode().put("message", sanitizer.text(request.getMessage()));
            ObjectNode output = mapper.createObjectNode();
            output.putObject("capture").put("inputTruncated", sanitizer.truncated());
            request.setEvaluationCaptureContext(new EvaluationCaptureContext(binding, input, output));
            count("started");
        } catch (Exception ignored) { count("snapshot_failed"); }
    }

    public void context(ChatRequest request, SessionContext context, TaskTriggerDecision decision) {
        snapshot(request, capture -> {
            capture.output.set("context", select(mapper.valueToTree(context), "userIntent", "complexity",
                    "conversationPhase", "messageCount", "contextVersion", "currentTopic", "pendingQuestions", "recentSummaries"));
            capture.output.set("routing", mapper.valueToTree(decision));
        });
    }

    public void agent(ChatRequest request, DoctorAssistantTaskResponse response) {
        if (response == null) { return; }
        snapshot(request, capture -> {
            capture.runId = response.getTraceContext() == null ? null : response.getTraceContext().getRunId();
            capture.output.set("agentResponse", select(mapper.valueToTree(response), "status", "taskType",
                    "patientSafeReply", "patientVisibleEvidence", "structuredResult", "executionPath",
                    "agentExecutionStatus", "fallbackReason", "needsHumanReview"));
        });
    }

    /** 流式网关已解析的 result 状态事件，不改变交付给客户端的事件。 */
    public void event(ChatRequest request, StreamEvent event) {
        if (event == null || event.getType() != StreamEvent.EventType.STATUS || event.getDetails() == null) { return; }
        if (event.getEvaluationTaskResponse() != null) {
            agent(request, event.getEvaluationTaskResponse());
            return;
        }
        snapshot(request, capture -> {
            JsonNode details = mapper.valueToTree(event.getDetails());
            if (details.path("runId").isTextual()) { capture.runId = details.path("runId").asText(); }
            if ("result".equals(event.getStage())) {
                ObjectNode response = select(details, "executionPath", "needsHumanReview", "fallbackReason", "patientVisibleEvidence");
                if (details.has("taskStatus")) { response.set("status", details.get("taskStatus")); }
                capture.output.set("agentResponse", response);
            }
        });
    }

    public void complete(ChatRequest request, ChatResponse response, Throwable error) {
        try {
            String failure = error != null || response == null || !Boolean.TRUE.equals(response.getSuccess()) ? "CHAT_FAILED" : null;
            finish(request, select(mapper.valueToTree(response), "success", "message", "patientVisibleEvidence"), null, failure, "SYNC");
        } catch (Exception ignored) { count("snapshot_failed"); }
    }

    public void completeStream(ChatRequest request, StreamResponseAccumulator accumulator, String signal) {
        try {
            String error = "cancel".equalsIgnoreCase(signal) ? "STREAM_CANCELLED"
                    : accumulator.hasError() ? "STREAM_FAILED" : !accumulator.isCompleted() ? "STREAM_INCOMPLETE" : null;
            ObjectNode response = mapper.createObjectNode().put("success", error == null).put("message", accumulator.getFullContent());
            Long tokens = accumulator.getTokenUsage() == null || accumulator.getTokenUsage().getTotalTokens() == null
                    ? null : accumulator.getTokenUsage().getTotalTokens().longValue();
            finish(request, response, tokens, error, "STREAM");
        } catch (Exception ignored) { count("snapshot_failed"); }
    }

    private void finish(ChatRequest request, JsonNode response, Long tokens, String error, String mode) {
        EvaluationCaptureContext capture = request.getEvaluationCaptureContext();
        if (capture == null || !capture.completed.compareAndSet(false, true)) { return; }
        CaptureSanitizer sanitizer = new CaptureSanitizer(mapper, properties, request);
        ObjectNode output;
        String runId;
        synchronized (capture) {
            output = capture.output.deepCopy();
            runId = capture.runId;
        }
        output.set("response", response);
        ObjectNode metadata = (ObjectNode) output.get("capture");
        metadata.put("mode", mode).put("requestIdHash", sanitizer.hash(request.getRequestId()))
                .put("conversationIdHash", sanitizer.hash(request.getConversationId()))
                .put("runIdHash", sanitizer.hash(runId));
        // 保留关联摘要字段名，不重复对它们做 ID 脱敏。
        ObjectNode sanitized = (ObjectNode) sanitizer.tree(output);
        if (sanitizer.truncated() || metadata.path("inputTruncated").asBoolean()) { error = "CAPTURE_TRUNCATED"; }
        ObjectNode observation = mapper.createObjectNode().put("captureId", capture.captureId)
                .put("caseId", capture.binding.caseId()).put("trial", capture.binding.trial())
                .put("latencyMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - capture.startedNanos));
        observation.set("input", capture.input.deepCopy());
        observation.set("output", sanitized);
        if (tokens == null || tokens < 0) { observation.putNull("totalTokens"); } else { observation.put("totalTokens", tokens); }
        observation.put("error", error);
        String capturedRunId = runId;
        try {
            executor.execute(() -> deliver(capture.binding.batchId(), capturedRunId, observation, sanitizer));
            count("queued");
        } catch (Exception ignored) { count("queue_rejected"); }
    }

    private void deliver(String batchId, String runId, ObjectNode observation, CaptureSanitizer sanitizer) {
        try {
            ObjectNode output = (ObjectNode) observation.get("output");
            ObjectNode metadata = (ObjectNode) output.get("capture");
            metadata.put("replayStatus", "NOT_AVAILABLE");
            if (runId != null && !runId.isBlank()) {
                try {
                    JsonNode replay = transport.replay(runId);
                    if (replay == null || !replay.isObject() || !runId.equals(replay.path("runId").asText())) {
                        throw new IllegalStateException("回放关联不匹配");
                    }
                    output.set("replay", sanitizer.tree(replayProjection(replay)));
                    metadata.put("replayStatus", "AVAILABLE");
                    if (sanitizer.truncated()) { observation.put("error", "CAPTURE_TRUNCATED"); }
                } catch (Exception ignored) {
                    metadata.put("replayStatus", "FAILED");
                    if (observation.path("error").isNull()) { observation.put("error", "CAPTURE_REPLAY_FAILED"); }
                    count("replay_failed");
                }
            }
            for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
                try { transport.publish(batchId, observation); count("delivered"); return; }
                catch (HttpClientErrorException permanent) { count("delivery_failed"); return; }
                catch (Exception transientFailure) {
                    if (attempt == properties.getMaxAttempts()) { count("delivery_failed"); return; }
                    count("retry");
                }
            }
        } catch (Exception ignored) { count("delivery_failed"); }
    }

    private ObjectNode replayProjection(JsonNode replay) {
        ObjectNode output = mapper.createObjectNode();
        output.set("run", select(replay.path("run"), "status", "runType", "startedAt", "endedAt"));
        output.set("outcome", select(replay.path("outcome"), "finalStatus", "outcomeLabel", "fallbackUsed",
                "manualReviewRequired", "evidenceSufficient", "toolErrorCount", "retryCount", "durationMs"));
        output.set("strategySnapshot", select(replay.path("strategySnapshot"), "promptVersion", "selectorVersion",
                "toolCatalogVersion", "memoryProfile", "timeoutMillis"));
        output.set("steps", projectArray(replay.path("steps"), "stepIndex", "nodeType", "nodeName", "status", "startedAt", "endedAt"));
        output.set("toolCalls", projectArray(replay.path("toolCalls"), "toolName", "status", "reason", "resultSummary"));
        output.set("approvalTasks", projectArray(replay.path("approvalTasks"), "taskName", "reason", "status"));
        return output;
    }

    private JsonNode projectArray(JsonNode source, String... fields) {
        if (!source.isArray()) { return mapper.nullNode(); }
        var result = mapper.createArrayNode();
        // 保留最多101条，让 sanitizer 能检测超限并将观测标为错误。
        for (int i = 0; i < source.size() && i < 101; i++) { result.add(select(source.get(i), fields)); }
        return result;
    }

    private ObjectNode select(JsonNode source, String... fields) {
        ObjectNode result = mapper.createObjectNode();
        if (source != null && source.isObject()) {
            for (String field : fields) { if (source.has(field)) { result.set(field, source.get(field).deepCopy()); } }
        }
        return result;
    }

    private void snapshot(ChatRequest request, Consumer<EvaluationCaptureContext> action) {
        try {
            EvaluationCaptureContext capture = request.getEvaluationCaptureContext();
            if (capture != null && !capture.completed.get()) { synchronized (capture) { action.accept(capture); } }
        } catch (Exception ignored) { count("snapshot_failed"); }
    }

    private void count(String result) {
        try { metrics.counter("ai_chat_evaluation_capture_total", "result", result).increment(); }
        catch (Exception ignored) { /* 可观测性不可改变用户链路。 */ }
    }

    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"); }

    private static void validateBase(String value) {
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("评测采集目标必须为不含凭证、查询和片段的 HTTP(S) 地址");
        }
    }

    @PreDestroy
    public void close() {
        if (executor instanceof ThreadPoolExecutor pool) { pool.shutdown(); }
    }
}
