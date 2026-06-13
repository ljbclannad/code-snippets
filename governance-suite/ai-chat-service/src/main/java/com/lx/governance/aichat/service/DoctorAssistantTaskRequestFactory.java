package com.lx.governance.aichat.service;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.dto.AgentTaskConstraints;
import com.lx.governance.aichat.dto.AgentTaskSource;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.UserProfileDTO;

/**
 * 负责将患者对话主链路中的上下文打包为 doctor-assistant-agent 输入。
 */
@Component
public class DoctorAssistantTaskRequestFactory {

    public DoctorAssistantTaskRequest buildPatientDialogRequest(
            ChatRequest request,
            SessionContext sessionContext,
            UserProfileDTO profileSnapshot,
            LayeredMemories memorySnapshot,
            TaskTriggerDecision triggerDecision,
            String correlationId) {
        return DoctorAssistantTaskRequest.builder()
                .taskType(triggerDecision != null ? triggerDecision.getTaskType() : null)
                .goal(resolveGoal(triggerDecision))
                .customerId(request != null ? request.getCustomerId() : null)
                .source(AgentTaskSource.PATIENT_DIALOG_AI_CHAT)
                .patientMessage(request != null ? request.getMessage() : null)
                .conversationId(request != null ? request.getConversationId() : null)
                .correlationId(correlationId)
                .sessionContext(sessionContext)
                .profileSnapshot(profileSnapshot)
                .memorySnapshot(memorySnapshot)
                .triggerDecision(triggerDecision)
                .constraints(defaultConstraints(request))
                .build();
    }

    private String resolveGoal(TaskTriggerDecision triggerDecision) {
        if (triggerDecision == null) {
            return "处理患者对话升级任务";
        }
        if (triggerDecision.getSummary() != null && !triggerDecision.getSummary().isBlank()) {
            return triggerDecision.getSummary();
        }
        if (triggerDecision.getTaskType() != null) {
            return "处理任务: " + triggerDecision.getTaskType().name();
        }
        return "处理患者对话升级任务";
    }

    private AgentTaskConstraints defaultConstraints(ChatRequest request) {
        String conversationId = request != null ? request.getConversationId() : "unknown";
        String message = request != null && request.getMessage() != null ? request.getMessage() : "";
        return AgentTaskConstraints.builder()
                .patientVisibleResponseOnly(Boolean.TRUE)
                .requireHumanReviewForHighRisk(Boolean.TRUE)
                .requireEvidence(Boolean.TRUE)
                .requireAuditTrail(Boolean.TRUE)
                .fallbackToDirectChatOnFailure(Boolean.TRUE)
                .timeoutMillis(8000)
                .idempotencyKey("patient-dialog:" + conversationId + ":" + Integer.toHexString(message.hashCode()))
                .build();
    }
}
