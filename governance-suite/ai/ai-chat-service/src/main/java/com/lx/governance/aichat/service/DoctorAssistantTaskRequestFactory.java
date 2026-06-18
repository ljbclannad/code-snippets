package com.lx.governance.aichat.service;

import org.springframework.stereotype.Component;

import com.lx.governance.aichat.config.AiChatAgentTaskProperties;
import com.lx.governance.aichat.dto.AgentTaskConstraints;
import com.lx.governance.aichat.dto.AgentTaskSource;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DoctorAssistantTaskRequest;
import com.lx.governance.aichat.dto.LayeredMemories;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.agentplatform.model.PlatformContext;
import com.lx.governance.agentplatform.model.PlatformContextBudget;
import com.lx.governance.agentplatform.model.PlatformContextChannel;
import com.lx.governance.agentplatform.model.PlatformContextDomain;
import com.lx.governance.agentplatform.model.PlatformContextField;
import com.lx.governance.agentplatform.model.PlatformContextVisibility;
import com.lx.governance.agentplatform.model.PlatformTraceContext;
import com.lx.governance.agentplatform.runtime.PlatformContextProjector;
import com.lx.governance.common.trace.Trace;

/**
 * 负责将患者对话主链路中的上下文打包为 doctor-assistant-agent 输入。
 */
@Component
public class DoctorAssistantTaskRequestFactory {

    private final AiChatAgentTaskProperties taskProperties;
    private final PlatformContextProjector platformContextProjector;

    public DoctorAssistantTaskRequestFactory(
            AiChatAgentTaskProperties taskProperties,
            PlatformContextProjector platformContextProjector) {
        this.taskProperties = taskProperties;
        this.platformContextProjector = platformContextProjector;
    }

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
                .traceContext(buildTraceContext(request, correlationId))
                .platformContext(projectPlatformContext(buildPlatformContext(
                        request,
                        sessionContext,
                        profileSnapshot,
                        memorySnapshot,
                        triggerDecision)))
                .sessionContext(sessionContext)
                .profileSnapshot(profileSnapshot)
                .memorySnapshot(memorySnapshot)
                .triggerDecision(triggerDecision)
                .constraints(defaultConstraints(request))
                .build();
    }

    private PlatformTraceContext buildTraceContext(ChatRequest request, String correlationId) {
        return PlatformTraceContext.builder()
                .traceId(Trace.getOrCreate())
                .conversationId(request != null ? request.getConversationId() : null)
                .correlationId(correlationId)
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

    private PlatformContext projectPlatformContext(PlatformContext platformContext) {
        if (platformContextProjector == null || platformContext == null) {
            return platformContext;
        }
        AiChatAgentTaskProperties.PlatformContextTransport transport = taskProperties.getPlatformContext();
        return platformContextProjector.project(
                platformContext,
                PlatformContextChannel.AGENT_INPUT,
                PlatformContextBudget.builder()
                        .maxFields(transport.getMaxFields())
                        .maxFieldChars(transport.getMaxFieldChars())
                        .maxTotalChars(transport.getMaxTotalChars())
                        .maxEvidences(transport.getMaxEvidences())
                        .maxToolOutputs(transport.getMaxToolOutputs())
                        .build());
    }

    private PlatformContext buildPlatformContext(
            ChatRequest request,
            SessionContext sessionContext,
            UserProfileDTO profileSnapshot,
            LayeredMemories memorySnapshot,
            TaskTriggerDecision triggerDecision) {
        PlatformContext.PlatformContextBuilder builder = PlatformContext.builder()
                .schemaVersion("platform-context/v1")
                .channel(PlatformContextChannel.SERVICE_TRANSPORT)
                .contextId("ctx:" + (request != null ? request.getConversationId() : "unknown"))
                .conversationId(request != null ? request.getConversationId() : null)
                .customerId(request != null ? request.getCustomerId() : null)
                .doctorId(null);
        if (sessionContext != null) {
            builder.field(PlatformContextField.builder()
                    .fieldKey("session_context")
                    .domain(PlatformContextDomain.CONVERSATION_CONTEXT)
                    .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                    .source("AI_CHAT_SESSION_CONTEXT")
                    .value(sessionContext)
                    .build());
        }
        if (profileSnapshot != null) {
            builder.field(PlatformContextField.builder()
                    .fieldKey("profile_snapshot")
                    .domain(PlatformContextDomain.USER_PROFILE_SNAPSHOT)
                    .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                    .source("PROFILE_SNAPSHOT")
                    .value(profileSnapshot)
                    .build());
        }
        if (memorySnapshot != null) {
            builder.field(PlatformContextField.builder()
                    .fieldKey("memory_snapshot")
                    .domain(PlatformContextDomain.LAYERED_MEMORIES)
                    .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                    .source("AI_CHAT_MEMORY")
                    .value(memorySnapshot)
                    .build());
        }
        if (triggerDecision != null) {
            builder.field(PlatformContextField.builder()
                    .fieldKey("trigger_decision")
                    .domain(PlatformContextDomain.TASK_TRIGGER_DECISION)
                    .visibility(PlatformContextVisibility.AUDIT_ONLY)
                    .source("TASK_TRIGGER_ROUTER")
                    .value(triggerDecision)
                    .build());
        }
        if (request != null && request.getMessage() != null) {
            builder.field(PlatformContextField.builder()
                    .fieldKey("patient_message")
                    .domain(PlatformContextDomain.CONVERSATION_CONTEXT)
                    .visibility(PlatformContextVisibility.AGENT_INTERNAL)
                    .source("PATIENT_MESSAGE")
                    .value(request.getMessage())
                    .build());
        }
        return builder.build();
    }

    private AgentTaskConstraints defaultConstraints(ChatRequest request) {
        String conversationId = request != null ? request.getConversationId() : "unknown";
        String message = request != null && request.getMessage() != null ? request.getMessage() : "";
        return AgentTaskConstraints.builder()
                .patientVisibleResponseOnly(taskProperties.isPatientVisibleResponseOnly())
                .requireHumanReviewForHighRisk(taskProperties.isRequireHumanReviewForHighRisk())
                .requireEvidence(taskProperties.isRequireEvidence())
                .requireAuditTrail(taskProperties.isRequireAuditTrail())
                .fallbackToDirectChatOnFailure(taskProperties.isFallbackToDirectChatOnFailure())
                .timeoutMillis(taskProperties.getTimeoutMillis())
                .idempotencyKey("patient-dialog:" + conversationId + ":" + Integer.toHexString(message.hashCode()))
                .build();
    }
}
