package com.health.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.health.agent.config.FollowUpReminderProperties;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.exception.AgentException;
import com.health.agent.metrics.AgentMetrics;
import com.health.agent.model.AgentStreamEventType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.model.AgentStreamEvent;
import com.health.agent.service.PatientTaskAgentService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@RestController
@RequestMapping("/api/patient-tasks")
@Tag(name = "患者任务分析", description = "患者来源任务分析接口")
public class PatientTaskController {

    private final PatientTaskAgentService patientTaskAgentService;
    private final ExecutorService streamExecutor;
    private final AgentAccessGuard accessGuard;
    private final AgentMetrics agentMetrics;
    private final FollowUpReminderProperties assignments;
    private final PatientTaskServiceAuth auth;

    public PatientTaskController(
            PatientTaskAgentService patientTaskAgentService,
            @Qualifier("patientTaskStreamExecutor") ExecutorService streamExecutor,
            AgentAccessGuard accessGuard,
            AgentMetrics agentMetrics, FollowUpReminderProperties assignments, PatientTaskServiceAuth auth) {
        this.patientTaskAgentService = patientTaskAgentService;
        this.streamExecutor = streamExecutor;
        this.accessGuard = accessGuard;
        this.agentMetrics = agentMetrics;
        this.assignments = assignments;
        this.auth = auth;
    }

    @Operation(
        summary = "分析患者来源任务",
        description = "接收患者对话侧触发的任务分析请求，结合患者消息、上下文快照和约束生成医生助手任务分析结果。",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = DoctorAssistantTaskRequest.class),
                examples = @ExampleObject(
                    name = "患者随访分析示例",
                    value = """
                        {
                          "taskType": "PATIENT_FOLLOW_UP_ANALYSIS",
                          "goal": "患者反馈最近一周晚餐控制后体重仍无明显下降，请判断是否进入平台期并给出下一步随访建议",
                          "customerId": "C10001",
                          "source": "PATIENT_DIALOG_AI_CHAT",
                          "patientMessage": "医生，我这周晚上基本没吃主食，但体重还是没怎么掉，是不是进入平台期了？",
                          "conversationId": "conv_20260616_001",
                          "correlationId": "corr_20260616_001",
                          "sessionContext": {
                            "channel": "patient_app",
                            "messageTime": "2026-06-16T10:15:00+08:00"
                          },
                          "profileSnapshot": {
                            "gender": "female",
                            "age": 32,
                            "heightCm": 165,
                            "currentWeightKg": 68.5,
                            "targetWeightKg": 60.0
                          },
                          "memorySnapshot": {
                            "shortTermMemories": [
                              "近7天体重在68.4kg到68.7kg之间波动",
                              "患者自述晚餐减少主食摄入"
                            ],
                            "longTermMemories": [
                              "3个月前开始减重管理",
                              "既往存在晚间加餐习惯"
                            ]
                          },
                          "triggerDecision": {
                            "triggered": true,
                            "reason": "患者主动询问平台期，需要生成结构化随访分析"
                          },
                          "constraints": {
                            "patientVisibleResponseOnly": false,
                            "requireHumanReviewForHighRisk": true,
                            "requireEvidence": true,
                            "requireAuditTrail": true,
                            "fallbackToDirectChatOnFailure": true,
                            "timeoutMillis": 15000,
                            "idempotencyKey": "pta-conv_20260616_001"
                          }
                        }
                        """
                )
            )
        )
    )
    @PostMapping("/analyze")
    public ResponseEntity<DoctorAssistantTaskResponse> analyze(@Valid @RequestBody DoctorAssistantTaskRequest request,
            @RequestHeader(value = "X-Follow-Up-Token", defaultValue = "") String token) {
        auth.require(token);
        if (!bindDoctor(request)) {
            return ResponseEntity.ok(unassigned(request));
        }
        // Controller 只负责请求接入、参数校验后的日志留痕，以及把分析流程委托给领域服务。
        log.info("收到患者来源任务分析请求 - taskType: {}, conversationId: {}, correlationId: {}",
                request.getTaskType(), request.getConversationId(), request.getCorrelationId());

        // 核心流程由 service 串联：
        // 1. 校验任务类型并初始化 taskId / session / goal
        // 2. 按 taskType 生成结构化分析结果，并判断是否包含高风险信号
        // 3. 对患者可见回复做安全收敛，必要时转人工复核 / 待审批
        // 4. 组装医生工作台载荷、审计上下文和最终响应
        try (AgentAccessGuard.AccessPermit permit = accessGuard.acquirePatientTask(request.getCustomerId())) {
            return ResponseEntity.ok(patientTaskAgentService.analyze(request));
        } catch (AgentException e) {
            if (allowDirectChatFallback(request)) {
                agentMetrics.recordEntryRejected("patient_task", summarizeReason(e.getMessage()), "fallback");
                return ResponseEntity.ok(patientTaskAgentService.buildEntryRejectedResponse(
                        request,
                        "PATIENT_TASK_ENTRY_REJECTED",
                        e.getMessage()));
            }
            agentMetrics.recordEntryRejected("patient_task", summarizeReason(e.getMessage()), "reject");
            throw e;
        }
    }

    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@Valid @RequestBody DoctorAssistantTaskRequest request,
            @RequestHeader(value = "X-Follow-Up-Token", defaultValue = "") String token) {
        auth.require(token);
        log.info("收到患者来源任务流式分析请求 - taskType: {}, conversationId: {}, correlationId: {}",
                request.getTaskType(), request.getConversationId(), request.getCorrelationId());

        SseEmitter emitter = new SseEmitter(120000L);
        if (!bindDoctor(request)) {
            emitFallbackAndComplete(emitter, unassigned(request));
            return emitter;
        }
        AgentAccessGuard.AccessPermit permit;
        try {
            permit = accessGuard.acquirePatientTask(request.getCustomerId());
        } catch (AgentException e) {
            if (allowDirectChatFallback(request)) {
                agentMetrics.recordEntryRejected("patient_task_stream", summarizeReason(e.getMessage()), "fallback");
                emitFallbackAndComplete(emitter, patientTaskAgentService.buildEntryRejectedResponse(
                        request,
                        "PATIENT_TASK_ENTRY_REJECTED",
                        e.getMessage()));
                return emitter;
            }
            agentMetrics.recordEntryRejected("patient_task_stream", summarizeReason(e.getMessage()), "reject");
            throw e;
        }

        try {
            CompletableFuture.runAsync(() -> {
            try {
                patientTaskAgentService.analyzeStream(request, event -> sendEvent(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.error("患者来源任务流式分析失败 - conversationId: {}, correlationId: {}",
                        request.getConversationId(), request.getCorrelationId(), e);
                emitter.completeWithError(e);
            } finally {
                permit.release();
            }
            }, streamExecutor);
        } catch (RejectedExecutionException e) {
            permit.release();
            if (allowDirectChatFallback(request)) {
                agentMetrics.recordEntryRejected("patient_task_stream", "PATIENT_TASK_STREAM_EXECUTOR_REJECTED", "fallback");
                emitFallbackAndComplete(emitter, patientTaskAgentService.buildEntryRejectedResponse(
                        request,
                        "PATIENT_TASK_STREAM_EXECUTOR_REJECTED",
                        "患者任务流式线程池已饱和"));
                return emitter;
            }
            agentMetrics.recordEntryRejected("patient_task_stream", "PATIENT_TASK_STREAM_EXECUTOR_REJECTED", "reject");
            throw new AgentException("PATIENT_TASK_STREAM_EXECUTOR_REJECTED: 患者任务流式线程池已饱和", e);
        }
        return emitter;
    }

    private boolean bindDoctor(DoctorAssistantTaskRequest request) {
        String doctor = assignments.assignedDoctor(request.getCustomerId());
        if (doctor == null || doctor.isBlank()) {
            return false;
        }
        request.setDoctorId(doctor);
        if (request.getPlatformContext() != null) {
            request.setPlatformContext(request.getPlatformContext().toBuilder().doctorId(doctor).build());
        }
        return true;
    }

    private DoctorAssistantTaskResponse unassigned(DoctorAssistantTaskRequest request) {
        return DoctorAssistantTaskResponse.builder().status(DoctorAssistantTaskStatus.SKIPPED)
                .taskType(request.getTaskType()).conversationId(request.getConversationId())
                .correlationId(request.getCorrelationId()).fallbackReason("DOCTOR_NOT_ASSIGNED")
                .patientSafeReply("当前尚未分配责任医生，本轮未创建复核任务，请直接联系医生。")
                .needsHumanReview(false).build();
    }

    private boolean allowDirectChatFallback(DoctorAssistantTaskRequest request) {
        return request != null
                && request.getConstraints() != null
                && Boolean.TRUE.equals(request.getConstraints().getFallbackToDirectChatOnFailure());
    }

    private String summarizeReason(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        int separatorIndex = message.indexOf(':');
        return separatorIndex > 0 ? message.substring(0, separatorIndex).trim() : message.trim();
    }

    private void emitFallbackAndComplete(SseEmitter emitter, DoctorAssistantTaskResponse response) {
        try {
            sendEvent(emitter, AgentStreamEvent.builder()
                    .sessionId(response != null ? response.getAgentSessionId() : null)
                    .step(0)
                    .type(AgentStreamEventType.TASK_RESULT)
                    .data(response)
                    .timestamp(LocalDateTime.now())
                    .build());
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void sendEvent(SseEmitter emitter, AgentStreamEvent event) {
        if (emitter == null || event == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType().name().toLowerCase())
                    .data(event));
        } catch (IOException e) {
            throw new IllegalStateException("发送患者任务 SSE 事件失败", e);
        }
    }
}
