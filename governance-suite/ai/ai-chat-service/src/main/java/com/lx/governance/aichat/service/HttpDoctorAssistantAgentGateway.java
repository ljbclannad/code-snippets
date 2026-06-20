package com.lx.governance.aichat.service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
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

    public HttpDoctorAssistantAgentGateway(
            @Qualifier("doctorAssistantAgentRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            @Value("${ai-chat.agent.enabled:false}") boolean enabled,
            @Value("${ai-chat.agent.base-url:http://localhost:8088}") String baseUrl,
            @Value("${ai-chat.agent.timeout-ms:15000}") int timeoutMs) {
        this.restClient = restClient;
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
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                        .build();
            } catch (Exception e) {
                sink.error(new AiServiceException(ErrorCode.AI_API_ERROR,
                        "构造 doctor-assistant-agent 流式请求失败: " + e.getMessage()));
                return;
            }

            try {
                HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 400) {
                    sink.error(new AiServiceException(ErrorCode.AI_API_ERROR,
                            "doctor-assistant-agent 流式调用失败: HTTP " + response.statusCode()));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    String eventName = null;
                    StringBuilder dataBuffer = new StringBuilder();
                    while (!sink.isCancelled() && (line = reader.readLine()) != null) {
                        if (line.isBlank()) {
                            emitRemoteEvent(request, eventName, dataBuffer, sink);
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
                    emitRemoteEvent(request, eventName, dataBuffer, sink);
                }

                if (!sink.isCancelled()) {
                    sink.complete();
                }
            } catch (Exception e) {
                if (!sink.isCancelled()) {
                    sink.error(new AiServiceException(ErrorCode.AI_API_ERROR,
                            "doctor-assistant-agent 流式调用失败: " + e.getMessage()));
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void emitRemoteEvent(
            DoctorAssistantTaskRequest request,
            String eventName,
            StringBuilder dataBuffer,
            reactor.core.publisher.FluxSink<StreamEvent> sink) {
        if (sink == null || sink.isCancelled() || dataBuffer == null || dataBuffer.length() == 0) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(dataBuffer.toString());
            String remoteType = root.path("type").asText();
            if (remoteType == null || remoteType.isBlank()) {
                remoteType = eventName != null ? eventName.toUpperCase() : "UNKNOWN";
            }
            JsonNode dataNode = root.get("data");
            for (StreamEvent event : mapRemoteEvent(request, remoteType, dataNode, root)) {
                if (event != null && !sink.isCancelled()) {
                    sink.next(event);
                }
            }
        } catch (Exception e) {
            log.warn("解析 doctor-assistant-agent SSE 事件失败 - eventName: {}, payload: {}",
                    eventName, dataBuffer, e);
        }
    }

    private List<StreamEvent> mapRemoteEvent(
            DoctorAssistantTaskRequest request,
            String remoteType,
            JsonNode dataNode,
            JsonNode root) {
        List<StreamEvent> events = new ArrayList<>();
        Map<String, Object> details = toMap(dataNode);
        if (!details.isEmpty()) {
            details.put("rawType", remoteType);
        }
        switch (remoteType) {
            case "SUBAGENT_STARTED" -> events.add(StreamEvent.status(
                    "agent",
                    subAgentStartedStatus(dataNode),
                    details));
            case "SUBAGENT_FINISHED" -> events.add(StreamEvent.status(
                    "agent",
                    subAgentFinishedStatus(dataNode),
                    details));
            case "THINKING_DELTA" -> events.add(StreamEvent.status(
                    "thinking",
                    textValue(dataNode, "Agent 正在推理中"),
                    details));
            case "THOUGHT" -> events.add(StreamEvent.status(
                    "thought",
                    textValue(dataNode, "Agent 已产出本轮思考"),
                    details));
            case "ACTION" -> events.add(StreamEvent.status(
                    "tool",
                    actionStatus(dataNode),
                    details));
            case "OBSERVATION" -> events.add(StreamEvent.status(
                    "observation",
                    textValue(dataNode, "工具返回了新的观察结果"),
                    details));
            case "CONTEXT_UPDATED" -> events.add(StreamEvent.status(
                    "context",
                    contextStatus(dataNode),
                    details));
            case "PENDING_APPROVAL" -> events.add(StreamEvent.status(
                    "approval",
                    "需要人工审批后才能继续执行",
                    details));
            case "FINAL_ANSWER" -> events.add(StreamEvent.status(
                    "final_answer",
                    "Agent 已生成结构化结论，正在整理患者回复",
                    details));
            case "DONE" -> events.add(StreamEvent.status(
                    "done",
                    "Agent 执行完成，正在回传结果",
                    details));
            case "ERROR" -> events.add(StreamEvent.status(
                    "error",
                    errorStatus(dataNode),
                    details));
            case "TASK_RESULT" -> events.addAll(taskResultEvents(request, dataNode));
            default -> events.add(StreamEvent.status(
                    "agent",
                    "收到下游事件: " + remoteType,
                    details));
        }
        if (!events.isEmpty() && root.path("sessionId").isTextual()) {
            for (StreamEvent event : events) {
                if (event.getDetails() == null) {
                    event.setDetails(new LinkedHashMap<>());
                }
                event.getDetails().putIfAbsent("agentSessionId", root.path("sessionId").asText());
            }
        }
        return events;
    }

    private List<StreamEvent> taskResultEvents(DoctorAssistantTaskRequest request, JsonNode dataNode) {
        List<StreamEvent> events = new ArrayList<>();
        DoctorAssistantTaskResponse response = objectMapper.convertValue(dataNode, DoctorAssistantTaskResponse.class);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rawType", "TASK_RESULT");
        details.put("taskStatus", response.getStatus() != null ? response.getStatus().name() : "UNKNOWN");
        details.put("executionPath", response.getExecutionPath());
        details.put("needsHumanReview", response.getNeedsHumanReview());
        details.put("agentSessionId", response.getAgentSessionId());
        details.put("fallbackReason", response.getFallbackReason());
        events.add(StreamEvent.status("result", "Agent 结果已回传患者对话链路", details));
        if (response.getPatientSafeReply() != null && !response.getPatientSafeReply().isBlank()) {
            events.add(StreamEvent.content(response.getPatientSafeReply()));
            events.add(StreamEvent.done(
                    response.getConversationId() != null ? response.getConversationId() : request.getConversationId(),
                    null));
        } else if (response.getErrorMessage() != null && !response.getErrorMessage().isBlank()) {
            events.add(StreamEvent.error(response.getErrorMessage(), 3002));
        } else {
            events.add(StreamEvent.error("doctor-assistant-agent 未返回患者可见回复", 3002));
        }
        return events;
    }

    private Map<String, Object> toMap(JsonNode dataNode) {
        if (dataNode == null || dataNode.isNull()) {
            return new LinkedHashMap<>();
        }
        if (dataNode.isObject()) {
            return objectMapper.convertValue(dataNode, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (dataNode.isArray()) {
            details.put("items", objectMapper.convertValue(dataNode, new TypeReference<List<Object>>() {
            }));
            return details;
        }
        details.put("value", dataNode.isTextual() ? dataNode.asText() : dataNode.toString());
        return details;
    }

    private String subAgentStartedStatus(JsonNode dataNode) {
        String name = dataNode != null ? dataNode.path("name").asText(null) : null;
        String responsibility = dataNode != null ? dataNode.path("responsibility").asText(null) : null;
        if (name != null && responsibility != null && !responsibility.isBlank()) {
            return "当前由 " + name + " 处理: " + responsibility;
        }
        if (name != null) {
            return "当前由 " + name + " 开始处理";
        }
        return "已切换到新的子 Agent";
    }

    private String subAgentFinishedStatus(JsonNode dataNode) {
        String name = dataNode != null ? dataNode.path("name").asText(null) : null;
        return name != null ? name + " 已完成当前阶段" : "子 Agent 已完成当前阶段";
    }

    private String actionStatus(JsonNode dataNode) {
        String toolName = dataNode != null ? dataNode.path("toolName").asText(null) : null;
        if (toolName != null && !toolName.isBlank()) {
            return "正在调用工具: " + toolName;
        }
        return "Agent 正在调用工具";
    }

    private String contextStatus(JsonNode dataNode) {
        String reason = dataNode != null ? dataNode.path("reason").asText(null) : null;
        return reason != null && !reason.isBlank() ? "上下文已更新: " + reason : "上下文已更新";
    }

    private String errorStatus(JsonNode dataNode) {
        if (dataNode == null || dataNode.isNull()) {
            return "Agent 执行出现错误";
        }
        if (dataNode.isTextual()) {
            return dataNode.asText();
        }
        String message = dataNode.path("message").asText(null);
        return message != null && !message.isBlank() ? message : "Agent 执行出现错误";
    }

    private String textValue(JsonNode dataNode, String fallback) {
        if (dataNode == null || dataNode.isNull()) {
            return fallback;
        }
        if (dataNode.isTextual()) {
            return dataNode.asText();
        }
        return dataNode.toString();
    }
}
