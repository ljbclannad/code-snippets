package com.health.agent.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.model.AgentStreamEvent;
import com.health.agent.service.PatientTaskAgentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@RestController
@RequestMapping("/api/patient-tasks")
@RequiredArgsConstructor
@Tag(name = "患者任务分析", description = "患者来源任务分析接口")
public class PatientTaskController {

    private final PatientTaskAgentService patientTaskAgentService;
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("patient-task-stream-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });

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
                          "doctorId": "D20001",
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
    public ResponseEntity<DoctorAssistantTaskResponse> analyze(@Valid @RequestBody DoctorAssistantTaskRequest request) {
        // Controller 只负责请求接入、参数校验后的日志留痕，以及把分析流程委托给领域服务。
        log.info("收到患者来源任务分析请求 - taskType: {}, conversationId: {}, correlationId: {}",
                request.getTaskType(), request.getConversationId(), request.getCorrelationId());

        // 核心流程由 service 串联：
        // 1. 校验任务类型并初始化 taskId / session / goal
        // 2. 按 taskType 生成结构化分析结果，并判断是否包含高风险信号
        // 3. 对患者可见回复做安全收敛，必要时转人工复核 / 待审批
        // 4. 组装医生工作台载荷、审计上下文和最终响应
        return ResponseEntity.ok(patientTaskAgentService.analyze(request));
    }

    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@Valid @RequestBody DoctorAssistantTaskRequest request) {
        log.info("收到患者来源任务流式分析请求 - taskType: {}, conversationId: {}, correlationId: {}",
                request.getTaskType(), request.getConversationId(), request.getCorrelationId());

        SseEmitter emitter = new SseEmitter(120000L);
        CompletableFuture.runAsync(() -> {
            try {
                patientTaskAgentService.analyzeStream(request, event -> sendEvent(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.error("患者来源任务流式分析失败 - conversationId: {}, correlationId: {}",
                        request.getConversationId(), request.getCorrelationId(), e);
                emitter.completeWithError(e);
            }
        }, streamExecutor);
        return emitter;
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
