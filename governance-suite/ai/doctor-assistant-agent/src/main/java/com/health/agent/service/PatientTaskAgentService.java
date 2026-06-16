package com.health.agent.service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.DoctorAssistantPendingTaskPayload;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.DoctorWorkbenchPayload;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.Step;
import com.health.agent.model.StructuredFinalAnswer;
import com.health.agent.model.TaskStatus;
import com.health.agent.safety.MedicalSafetyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
public class PatientTaskAgentService {

    private final PatientTaskSafetyService safetyService;
    private final AgentSessionService sessionService;
    private final AgentSessionWorkflowService workflowService;
    private final MedicalSafetyService medicalSafetyService;

    public PatientTaskAgentService(PatientTaskSafetyService safetyService) {
        this(safetyService, null, null, null);
    }

    @Autowired
    public PatientTaskAgentService(
        PatientTaskSafetyService safetyService,
        AgentSessionService sessionService,
        AgentSessionWorkflowService workflowService,
        MedicalSafetyService medicalSafetyService
    ) {
        this.safetyService = safetyService;
        this.sessionService = sessionService;
        this.workflowService = workflowService;
        this.medicalSafetyService = medicalSafetyService;
    }

    public DoctorAssistantTaskResponse analyze(DoctorAssistantTaskRequest request) {
        if (request == null || request.getTaskType() == null || request.getTaskType() == TaskType.NONE) {
            return DoctorAssistantTaskResponse.builder()
                    .status(DoctorAssistantTaskStatus.SKIPPED)
                    .taskType(TaskType.NONE)
                    .errorMessage("未提供可执行的患者来源任务类型")
                    .build();
        }

        String taskId = buildTaskId(request);
        boolean persistenceEnabled = sessionService != null && workflowService != null;
        String goal = defaultValue(request.getGoal(), "患者来源任务: " + request.getTaskType().name());
        AgentSession session = persistenceEnabled
                ? sessionService.createSession(request.getCustomerId(), request.getDoctorId(), goal)
                : null;
        String agentSessionId = session != null ? String.valueOf(session.getId()) : "patient-agent-" + taskId;
        boolean highRisk = containsHighRiskSignal(request.getPatientMessage());

        StructuredTaskResult structuredResult = switch (request.getTaskType()) {
            case PATIENT_FOLLOW_UP_ANALYSIS -> buildFollowUpAnalysis(request, highRisk);
            case RISK_STRATIFICATION_AND_ESCALATION -> buildRiskStratification(request, highRisk);
            case NEXT_FOLLOW_UP_ACTION_RECOMMENDATION -> buildNextActionRecommendation(request, highRisk);
            default -> StructuredTaskResult.builder()
                    .summary("当前任务类型尚未支持")
                    .riskLevel("LOW")
                    .needsAttention(false)
                    .supportingEvidence(List.of("TASK_TYPE_NOT_SUPPORTED"))
                    .evidenceSources(List.of("PATIENT_TASK_AGENT"))
                    .build();
        };

        boolean needsHumanReview = highRisk || request.getTaskType() == TaskType.RISK_STRATIFICATION_AND_ESCALATION;
        String patientReply = buildPatientSafeReply(request.getTaskType(), structuredResult, needsHumanReview);
        PatientTaskSafetyService.SafetyResult safetyResult = safetyService.sanitize(patientReply, structuredResult, needsHumanReview);

        if (safetyResult.blocked()) {
            needsHumanReview = true;
            patientReply = safetyResult.reply();
            structuredResult.setNeedsAttention(Boolean.TRUE);
            structuredResult.setRiskLevel("HIGH");
            structuredResult.getUncertainties().add("触发患者侧安全拦截: " + safetyResult.category());
        } else {
            patientReply = safetyResult.reply();
        }

        AgentContext context = buildContext(request, goal, structuredResult);
        List<Step> history = buildHistory(request, structuredResult, patientReply, needsHumanReview);
        List<Message> messages = List.of(
                Message.user(request.getPatientMessage()),
                Message.assistant(patientReply)
        );

        DoctorAssistantPendingTaskPayload pendingTaskPayload = null;
        if (needsHumanReview) {
            PendingTask pendingTask = PendingTask.builder()
                    .agentSessionId(agentSessionId)
                    .customerId(request.getCustomerId())
                    .doctorId(request.getDoctorId())
                    .toolName("patient_follow_up_review")
                    .toolDescription("患者来源任务需要医生进一步确认")
                    .inputJson(JSON.toJSONString(Map.of(
                            "taskType", request.getTaskType().name(),
                            "patientMessage", request.getPatientMessage(),
                            "structuredResult", structuredResult)))
                    .reason("高风险/需人工复核的患者来源任务")
                    .status(TaskStatus.PENDING.name())
                    .createdAt(LocalDateTime.now())
                    .expireAt(LocalDateTime.now().plusHours(1))
                    .build();

            if (persistenceEnabled) {
                AgentResult auditResult = AgentResult.pendingApproval(agentSessionId, pendingTask, history, history.size());
                pendingTask = workflowService.pauseSessionForApproval(
                        agentSessionId,
                        history,
                        messages,
                        context,
                        pendingTask,
                        goal,
                        auditResult,
                        history.size(),
                        0L
                );
            }

            pendingTaskPayload = DoctorAssistantPendingTaskPayload.builder()
                    .pendingTaskId(pendingTask.getId() != null ? pendingTask.getId() : Math.abs(taskId.hashCode()) + 0L)
                    .agentSessionId(agentSessionId)
                    .toolName(pendingTask.getToolName())
                    .toolDescription(pendingTask.getToolDescription())
                    .reason(pendingTask.getReason())
                    .status(pendingTask.getStatus())
                    .expireAt(pendingTask.getExpireAt() != null
                            ? pendingTask.getExpireAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                            : Instant.now().plusSeconds(3600).toEpochMilli())
                    .build();
        } else if (persistenceEnabled) {
            StructuredFinalAnswer finalAnswer = toFinalAnswer(structuredResult);
            AgentResult auditResult = AgentResult.success(agentSessionId, finalAnswer, history, history.size());
            workflowService.completeSession(
                    agentSessionId,
                    request.getCustomerId(),
                    request.getDoctorId(),
                    goal,
                    history,
                    context,
                    auditResult,
                    history.size(),
                    0L
            );
        }

        DoctorWorkbenchPayload workbenchPayload = DoctorWorkbenchPayload.builder()
                .agentSessionId(agentSessionId)
                .taskId(taskId)
                .conversationId(request.getConversationId())
                .correlationId(request.getCorrelationId())
                .source(request.getSource() == null ? AgentTaskSource.PATIENT_DIALOG_AI_CHAT.name() : request.getSource().name())
                .auditTag("PATIENT_DIALOG_AGENT_TASK")
                .reviewStatus(needsHumanReview ? "PENDING_REVIEW" : "AUTO_COMPLETED")
                .taskType(request.getTaskType().name())
                .riskLevel(structuredResult.getRiskLevel())
                .needsAttention(structuredResult.getNeedsAttention())
                .needsHumanReview(needsHumanReview)
                .evidenceChainSummary(String.join(" | ", structuredResult.getSupportingEvidence()))
                .keyFindings(structuredResult.getKeyFindings())
                .recommendedActions(structuredResult.getRecommendedActions())
                .evidenceSources(structuredResult.getEvidenceSources())
                .uncertainties(structuredResult.getUncertainties())
                .pendingTask(pendingTaskPayload)
                .build();

        log.info("患者来源任务分析完成 - taskType: {}, taskId: {}, conversationId: {}, correlationId: {}, sessionId: {}",
                request.getTaskType(), taskId, request.getConversationId(), request.getCorrelationId(), agentSessionId);

        return DoctorAssistantTaskResponse.builder()
                .agentSessionId(agentSessionId)
                .taskId(taskId)
                .conversationId(request.getConversationId())
                .correlationId(request.getCorrelationId())
                .status(needsHumanReview ? DoctorAssistantTaskStatus.PENDING_APPROVAL : DoctorAssistantTaskStatus.SUCCESS)
                .taskType(request.getTaskType())
                .patientSafeReply(patientReply)
                .structuredResult(structuredResult)
                .doctorWorkbenchPayload(workbenchPayload)
                .needsHumanReview(needsHumanReview)
                .pendingTask(pendingTaskPayload)
                .build();
    }

    private AgentContext buildContext(
        DoctorAssistantTaskRequest request,
        String goal,
        StructuredTaskResult structuredResult
    ) {
        AgentContext context = AgentContext.initialize(request.getCustomerId(), request.getDoctorId(), goal);
        context.putSlot("patient_task_request", Map.of(
                "taskType", request.getTaskType().name(),
                "conversationId", request.getConversationId(),
                "correlationId", request.getCorrelationId()
        ), "PATIENT_TASK_AGENT");
        if (request.getSessionContext() != null && !request.getSessionContext().isEmpty()) {
            context.putSlot("session_context", request.getSessionContext(), "AI_CHAT_SESSION_CONTEXT");
        }
        if (request.getProfileSnapshot() != null && !request.getProfileSnapshot().isEmpty()) {
            context.putSlot("profile_snapshot", request.getProfileSnapshot(), "PROFILE_SNAPSHOT");
        }
        if (request.getMemorySnapshot() != null) {
            context.putSlot("memory_snapshot", request.getMemorySnapshot(), "AI_CHAT_MEMORY");
        }
        context.putSlot("patient_task_result", structuredResult, "PATIENT_TASK_AGENT");
        context.addEvidence("patient_task_analysis", "PATIENT_MESSAGE", request.getPatientMessage(), Map.of(
                "taskType", request.getTaskType().name(),
                "conversationId", request.getConversationId()
        ));
        return context;
    }

    private List<Step> buildHistory(
        DoctorAssistantTaskRequest request,
        StructuredTaskResult structuredResult,
        String patientReply,
        boolean needsHumanReview
    ) {
        List<Step> history = new ArrayList<>();
        history.add(Step.thought("已针对患者来源任务生成结构化分析。"));
        history.add(Step.observation("患者消息: " + request.getPatientMessage()));
        history.add(Step.observation("结构化摘要: " + structuredResult.getSummary()));
        history.add(Step.observation("患者可见回复: " + patientReply));
        if (needsHumanReview) {
            history.add(Step.observation("已转入医生人工复核队列。"));
        }
        return history;
    }

    private StructuredFinalAnswer toFinalAnswer(StructuredTaskResult structuredResult) {
        return StructuredFinalAnswer.builder()
                .summary(structuredResult.getSummary())
                .riskLevel(structuredResult.getRiskLevel())
                .needsAttention(Boolean.TRUE.equals(structuredResult.getNeedsAttention()))
                .keyFindings(structuredResult.getKeyFindings())
                .recommendedActions(structuredResult.getRecommendedActions())
                .uncertainties(structuredResult.getUncertainties())
                .supportingEvidence(structuredResult.getSupportingEvidence())
                .evidenceSources(structuredResult.getEvidenceSources())
                .inferences(structuredResult.getInferences())
                .build();
    }

    private StructuredTaskResult buildFollowUpAnalysis(DoctorAssistantTaskRequest request, boolean highRisk) {
        int shortMemoryCount = countMemories(request.getMemorySnapshot(), true);
        int longMemoryCount = countMemories(request.getMemorySnapshot(), false);
        String phase = stringValue(request.getSessionContext(), "conversationPhase");
        return StructuredTaskResult.builder()
                .summary("基于当前会话阶段、画像快照和历史记忆，已形成患者随访分析摘要。")
                .riskLevel(highRisk ? "HIGH" : "MEDIUM")
                .needsAttention(Boolean.TRUE)
                .keyFindings(List.of(
                        "当前对话阶段: " + defaultValue(phase, "unknown"),
                        "已纳入短期记忆 " + shortMemoryCount + " 条、长期记忆 " + longMemoryCount + " 条",
                        "患者本轮消息已与历史随访上下文合并分析"))
                .recommendedActions(List.of(
                        "继续补充近一周关键监测数据",
                        "结合医生建议复核近期症状与执行情况"))
                .uncertainties(new ArrayList<>(List.of("仍需结合线下检查或医生复核确认最终结论")))
                .supportingEvidence(List.of(
                        "SESSION_CONTEXT_AVAILABLE",
                        "MEMORY_SNAPSHOT_ATTACHED"))
                .evidenceSources(List.of("AI_CHAT_SESSION_CONTEXT", "AI_CHAT_MEMORY_SNAPSHOT"))
                .inferences(List.of("适合进入结构化随访分析链路"))
                .build();
    }

    private StructuredTaskResult buildRiskStratification(DoctorAssistantTaskRequest request, boolean highRisk) {
        return StructuredTaskResult.builder()
                .summary(highRisk
                        ? "检测到高风险信号，建议立即转人工医生评估。"
                        : "当前风险可先按中等风险纳入人工复核队列。")
                .riskLevel(highRisk ? "HIGH" : "MEDIUM")
                .needsAttention(Boolean.TRUE)
                .keyFindings(List.of(
                        highRisk ? "消息中包含高危症状或紧急信号" : "当前未见急危重词，但已达到升级分析阈值",
                        "患者来源任务不直接对外暴露内部分层细节"))
                .recommendedActions(List.of(
                        "尽快由医生或健康管理师人工复核",
                        "必要时指导患者线下就医"))
                .uncertainties(new ArrayList<>(List.of("线上信息无法替代医生面对面评估")))
                .supportingEvidence(List.of(
                        "PATIENT_MESSAGE_TRIGGERED",
                        "TASK_TYPE_RISK_STRATIFICATION"))
                .evidenceSources(List.of("PATIENT_MESSAGE", "TRIGGER_DECISION"))
                .inferences(List.of("当前任务应优先保障人工接管"))
                .build();
    }

    private StructuredTaskResult buildNextActionRecommendation(DoctorAssistantTaskRequest request, boolean highRisk) {
        String currentTopic = stringValue(request.getSessionContext(), "currentTopic");
        return StructuredTaskResult.builder()
                .summary("已为当前患者对话生成保守的下一步随访动作建议。")
                .riskLevel(highRisk ? "HIGH" : "LOW")
                .needsAttention(highRisk)
                .keyFindings(List.of(
                        "当前主题: " + defaultValue(currentTopic, "未明确"),
                        "建议优先收集新增症状、监测值和执行偏差"))
                .recommendedActions(List.of(
                        "补充近 3-7 天监测数据",
                        "确认是否存在新发不适或执行中断",
                        highRisk ? "立即联系医生进一步评估" : "在医生建议下安排下一次随访"))
                .uncertainties(new ArrayList<>(List.of("具体随访频率仍需结合医生计划确认")))
                .supportingEvidence(List.of(
                        "SESSION_TOPIC_AVAILABLE",
                        "FOLLOW_UP_ACTION_TEMPLATE"))
                .evidenceSources(List.of("AI_CHAT_SESSION_CONTEXT", "PATIENT_TASK_AGENT"))
                .inferences(List.of("当前适合给出保守型下一步动作建议"))
                .build();
    }

    private String buildPatientSafeReply(TaskType taskType, StructuredTaskResult structuredResult, boolean needsHumanReview) {
        return switch (taskType) {
            case PATIENT_FOLLOW_UP_ANALYSIS ->
                    "我先帮你整理了这次随访里最需要关注的信息，建议继续补充近几天的关键数据和变化情况。"
                    + (needsHumanReview ? " 这部分也会同步给医生进一步查看。" : "");
            case RISK_STRATIFICATION_AND_ESCALATION ->
                    "结合你刚才提供的情况，建议尽快联系医生进一步评估；如果症状明显加重，请及时线下就医。";
            case NEXT_FOLLOW_UP_ACTION_RECOMMENDATION ->
                    "下一步建议先补充最近几天的监测结果、症状变化和执行情况，再结合医生意见安排后续随访。";
            default -> Objects.requireNonNullElse(structuredResult.getSummary(), "已收到你的情况，我们会继续谨慎评估。");
        };
    }

    private boolean containsHighRiskSignal(String patientMessage) {
        return medicalSafetyService != null && (
                medicalSafetyService.hasEmergencySignal(patientMessage)
                        || medicalSafetyService.hasPrescriptionRisk(patientMessage)
        );
    }

    private int countMemories(MemorySnapshot memorySnapshot, boolean shortTerm) {
        if (memorySnapshot == null) {
            return 0;
        }
        return shortTerm ? memorySnapshot.getShortTermMemories().size() : memorySnapshot.getLongTermMemories().size();
    }

    private String stringValue(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String buildTaskId(DoctorAssistantTaskRequest request) {
        String seed = defaultValue(request.getConversationId(), UUID.randomUUID().toString())
                + "|"
                + defaultValue(request.getCorrelationId(), "no-correlation")
                + "|"
                + request.getTaskType().name();
        return Integer.toHexString(seed.hashCode());
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
