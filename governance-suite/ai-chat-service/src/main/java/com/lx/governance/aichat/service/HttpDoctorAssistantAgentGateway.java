package com.lx.governance.aichat.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.exception.AiServiceException;
import com.lx.governance.aichat.exception.ErrorCode;

import lombok.extern.slf4j.Slf4j;

/**
 * 基于 HTTP 的 doctor-assistant-agent 调用网关。
 */
@Slf4j
@Component
public class HttpDoctorAssistantAgentGateway implements DoctorAssistantAgentGateway {

    private final RestClient restClient;
    private final boolean enabled;

    public HttpDoctorAssistantAgentGateway(
            @Qualifier("doctorAssistantAgentRestClient") RestClient restClient,
            @Value("${ai-chat.agent.enabled:false}") boolean enabled) {
        this.restClient = restClient;
        this.enabled = enabled;
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

            log.info("doctor-assistant-agent 调用完成 - taskType: {}, status: {}, agentSessionId: {}, correlationId: {}",
                    response.getTaskType(),
                    response.getStatus(),
                    response.getAgentSessionId(),
                    correlationId);
            return response;
        } catch (Exception e) {
            throw new AiServiceException(ErrorCode.AI_API_ERROR,
                    "doctor-assistant-agent 调用失败: " + e.getMessage());
        }
    }
}
