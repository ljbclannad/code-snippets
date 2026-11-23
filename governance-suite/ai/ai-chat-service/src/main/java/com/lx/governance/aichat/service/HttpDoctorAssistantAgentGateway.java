package com.lx.governance.aichat.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.StreamEvent;
import com.lx.governance.aichat.exception.AiServiceException;
import com.lx.governance.aichat.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 基于 HTTP 的 doctor-assistant-agent 调用网关。
 */
@Slf4j
@Component
public class HttpDoctorAssistantAgentGateway implements DoctorAssistantAgentGateway {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final String serviceToken;

    public HttpDoctorAssistantAgentGateway(
            @Qualifier("doctorAssistantAgentRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            @Value("${ai-chat.agent.enabled:false}") boolean enabled,
            @Value("${ai-chat.agent.base-url:http://localhost:8088}") String baseUrl,
            @Value("${ai-chat.agent.timeout-ms:15000}") int timeoutMs,
            @Value("${ai-chat.follow-up-reminder.service-token:}") String serviceToken) {
        this.restClient = restClient;
        this.serviceToken = serviceToken;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
    }

    @Override
    public DoctorAssistantTaskResponse analyzePatientTask(DoctorAssistantTaskRequest request) {
        if (!enabled) {
            throw new AiServiceException(ErrorCode.AI_API_ERROR, "doctor-assistant-agent 未启用");
        }

        String correlationId = request != null ? request.getCorrelationId() : null;
        log.info("开始调用 doctor-assistant-agent - taskType: {}, conversationId: {}, correlationId: {}",
                request != null ? request.getTaskType() : null,
                request != null ? request.getConversationId() : null,
                correlationId);

        try {
            DoctorAssistantTaskResponse response = restClient.post()
                    .uri("/api/patient-tasks/analyze")
                    .header("X-Follow-Up-Token", serviceToken)
                    .body(request)
                    .retrieve()
                    .body(DoctorAssistantTaskResponse.class);

            if (response == null) {
                throw new AiServiceException(ErrorCode.AI_API_ERROR, "doctor-assistant-agent 返回为空");
            }

            log.info("doctor-assistant-agent 调用完成 - taskType: {}, status: {}, executionPath: {}, agentExecutionStatus: {}, fallbackReason: {}, needsHumanReview: {}, agentSessionId: {}, correlationId: {}",
                    response.getTaskType(),
                    response.getStatus(),
                    response.getExecutionPath(),
                    response.getAgentExecutionStatus(),
                    response.getFallbackReason(),
                    response.getNeedsHumanReview(),
                    response.getAgentSessionId(),
                    correlationId);
            return response;
        } catch (Exception e) {
            throw new AiServiceException(ErrorCode.AI_API_ERROR,
                    "doctor-assistant-agent 调用失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<StreamEvent> analyzePatientTaskStream(DoctorAssistantTaskRequest request) {
        if (!enabled) {
            return Flux.error(new AiServiceException(ErrorCode.AI_API_ERROR, "doctor-assistant-agent 未启用"));
        }

        String correlationId = request != null ? request.getCorrelationId() : null;
        log.info("开始流式调用 doctor-assistant-agent - taskType: {}, conversationId: {}, correlationId: {}",
                request != null ? request.getTaskType() : null,
                request != null ? request.getConversationId() : null,
                correlationId);

        return Flux.<StreamEvent>create(sink -> {
            HttpRequest httpRequest;
            try {
                httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/patient-tasks/analyze/stream"))
                        .timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("X-Follow-Up-Token", serviceToken)
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                        .build();
            } catch (Exception e) {
                sink.error(new AiServiceException(ErrorCode.AI_API_ERROR,
                        "构造 doctor-assistant-agent 流式请求失败: " + e.getMessage()));
                return;
            }

            try {
                var pending = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                var active = new AtomicReference<InputStream>();
                sink.onCancel(() -> {
                    pending.cancel(true);
                    InputStream body = active.get();
                    if (body != null) {
                        try {
                            body.close();
                        } catch (IOException ex) {
                            log.debug("取消流式读取时连接已关闭", ex);
                        }
                    }
                });
                HttpResponse<InputStream> response = pending.join();
                try (InputStream body = response.body();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    active.set(body);
                    if (sink.isCancelled()) {
                        return;
                    }
                    if (response.statusCode() != 200) {
                        throw new IOException("医生助手流式服务响应异常");
                    }
                    String line;
                    String eventName = null;
                    StringBuilder dataBuffer = new StringBuilder();
                    while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            if (emitRemoteEvent(request, eventName, dataBuffer, sink)) {
                                sink.complete();
                                return;
                            }
                            eventName = null;
                            dataBuffer.setLength(0);
                            continue;
                        }
                        if (line.startsWith("event:")) {
                            eventName = line.substring("event:".length()).trim();
                            continue;
                        }
                        if (line.startsWith("data:")) {
                            if (dataBuffer.length() > 0) {
                                dataBuffer.append('\n');
                            }
                            dataBuffer.append(line.substring("data:".length()).trim());
                        }
                    }
                    if (!sink.isCancelled()) {
                        if (!emitRemoteEvent(request, eventName, dataBuffer, sink)) {
                            throw new IOException("医生助手未返回最终结果");
                        }
                        sink.complete();
                    }
                }
            } catch (Exception e) {
                if (!sink.isCancelled()) {
                    sink.error(new AiServiceException(ErrorCode.AI_API_ERROR,
                            "doctor-assistant-agent 流式调用失败: " + e.getMessage()));
                }
            }
        }).subscribeOn(Schedulers.boundedElastic()).timeout(timeout);
    }

    private boolean emitRemoteEvent(
            DoctorAssistantTaskRequest request,
            String eventName,
            StringBuilder dataBuffer,
            reactor.core.publisher.FluxSink<StreamEvent> sink) {
        if (sink == null || sink.isCancelled() || dataBuffer == null || dataBuffer.length() == 0) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(dataBuffer.toString());
            String remoteType = root.path("type").asText();
            if (remoteType == null || remoteType.isBlank()) {
                remoteType = eventName != null ? eventName.toUpperCase() : "UNKNOWN";
            }
            JsonNode dataNode = root.get("data");
            for (StreamEvent event : mapRemoteEvent(request, remoteType, dataNode)) {
                if (event != null && !sink.isCancelled()) {
                    sink.next(event);
                }
            }
            return "TASK_RESULT".equals(remoteType);
        } catch (Exception e) {
            throw new IllegalStateException("医生助手事件格式无效", e);
        }
    }

    private List<StreamEvent> mapRemoteEvent(
            DoctorAssistantTaskRequest request,
            String remoteType,
            JsonNode dataNode) {
        return switch (remoteType) {
            case "SUBAGENT_STARTED" -> List.of(StreamEvent.status("agent", "正在分析你的问题", null));
            case "FINAL_ANSWER" -> List.of(StreamEvent.status("agent", "正在整理回复", null));
            case "TASK_RESULT" -> taskResultEvents(request, dataNode);
            default -> List.of();
        };
    }

    private List<StreamEvent> taskResultEvents(DoctorAssistantTaskRequest request, JsonNode dataNode) {
        List<StreamEvent> events = new ArrayList<>();
        DoctorAssistantTaskResponse response = objectMapper.convertValue(dataNode, DoctorAssistantTaskResponse.class);
        StreamEvent resultEvent = StreamEvent.status("result", "分析已结束", null);
        resultEvent.setEvaluationTaskResponse(response);
        events.add(resultEvent);
        if (response.getPatientVisibleEvidence() != null && !response.getPatientVisibleEvidence().isEmpty()) {
            events.add(StreamEvent.status("evidence", "已附带患者可见证据卡片", Map.of(
                    "items", response.getPatientVisibleEvidence(),
                    "count", response.getPatientVisibleEvidence().size())));
        }
        if (response.getPatientSafeReply() != null && !response.getPatientSafeReply().isBlank()) {
            events.add(StreamEvent.content(response.getPatientSafeReply()));
            events.add(StreamEvent.done(
                    request.getConversationId(),
                    null));

        } else {
            events.add(StreamEvent.error("本轮分析未能完成，请稍后再试。", 3002));
        }
        return events;
    }

}
