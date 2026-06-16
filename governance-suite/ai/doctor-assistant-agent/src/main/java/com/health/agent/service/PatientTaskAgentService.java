package com.health.agent.service;

import com.alibaba.fastjson2.JSON;
import com.health.agent.dto.agent.AgentExecutionCommand;
import com.health.agent.dto.patient.DoctorAssistantPendingTaskPayload;
import com.health.agent.dto.patient.DoctorAssistantTaskRequest;
import com.health.agent.dto.patient.DoctorAssistantTaskResponse;
import com.health.agent.dto.patient.DoctorAssistantTaskStatus;
import com.health.agent.dto.patient.DoctorWorkbenchPayload;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.StructuredTaskResult;
import com.health.agent.dto.patient.TaskType;
import com.health.agent.engine.ReActAgentEngine;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentResult;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.ResultStatus;
import com.health.agent.model.Step;
import com.health.agent.model.StructuredFinalAnswer;
import com.health.agent.model.TaskStatus;
import com.health.agent.safety.MedicalSafetyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class PatientTaskAgentService {

    private final PatientTaskSafetyService safetyService;
    private final AgentSessionService sessionService;
    private final AgentSessionWorkflowService workflowService;
    private final MedicalSafetyService medicalSafetyService;
    private final ReActAgentEngine agentEngine;
    private final PatientTaskAgentResultMapper resultMapper;
    private final PatientReplyComposer patientReplyComposer;

    public PatientTaskAgentService(PatientTaskSafetyService safetyService) {
        this(safetyService, null, null, null, null, null, null);
    }

    @Autowired
    public PatientTaskAgentService(
        PatientTaskSafetyService safetyService,
        AgentSessionService sessionService,
        AgentSessionWorkflowService workflowService,
        MedicalSafetyService medicalSafetyService,
        ReActAgentEngine agentEngine,
        PatientTaskAgentResultMapper resultMapper,
        PatientReplyComposer patientReplyComposer
    ) {
        this.safetyService = safetyService;
        this.sessionService = sessionService;
        this.workflowService = workflowService;
        this.medicalSafetyService = medicalSafetyService;
        this.agentEngine = agentEngine;
        this.resultMapper = resultMapper;
        this.patientReplyComposer = patientReplyComposer;
    }

    /**
     * 患者来源任务分析主入口。
     *
     * <p>当前实现会优先尝试进入真实 {@code ReActAgentEngine.executeStream(...)}，
     * 若引擎不可用、执行异常，或返回 ERROR / MAX_STEPS / 空结果，则回退到本地模板分析链路，
     * 以保证患者侧总能拿到稳定响应。</p>
     */
    public DoctorAssistantTaskResponse analyze(DoctorAssistantTaskRequest request) {
        // 入口兜底：没有 taskType，或者显式传 NONE，说明这次患者消息不需要进入任务分析链路，直接跳过。
        if (request == null || request.getTaskType() == null || request.getTaskType() == TaskType.NONE) {
            return DoctorAssistantTaskResponse.builder()
                    .status(DoctorAssistantTaskStatus.SKIPPED)
                    .taskType(TaskType.NONE)
                    .errorMessage("未提供可执行的患者来源任务类型")
                    .build();
        }

        // 先把患者升级请求归一化为统一内部执行命令，后续接真实 Agent 时直接复用这份命令对象。
        AgentExecutionCommand command = request.toExecutionCommand();
        if (agentEngine == null) {
            log.warn("患者来源任务分析未接入真实 Agent，引擎为空，回退模板分析 - taskType: {}, conversationId: {}, correlationId: {}",
                    command.getTaskType(), command.getConversationId(), command.getCorrelationId());
            return analyzeWithTemplateFallback(request, command, "AGENT_NOT_AVAILABLE");
        }

        try {
            AgentResult agentResult = agentEngine.executeStream(command, event -> {
            });
            if (agentResult == null) {
                log.warn("真实 Agent 返回为空，回退模板分析 - taskType: {}, conversationId: {}, correlationId: {}",
                        command.getTaskType(), command.getConversationId(), command.getCorrelationId());
                return analyzeWithTemplateFallback(request, command, "AGENT_RESULT_NULL");
            }
            if (agentResult.getStatus() == ResultStatus.ERROR || agentResult.getStatus() == ResultStatus.MAX_STEPS) {
                log.warn("真实 Agent 未稳定收敛，回退模板分析 - status: {}, conversationId: {}, correlationId: {}",
                        agentResult.getStatus(), command.getConversationId(), command.getCorrelationId());
                return analyzeWithTemplateFallback(request, command, "AGENT_STATUS_" + agentResult.getStatus().name());
            }
            return buildAgentBackedResponse(request, command, agentResult);
        } catch (Exception e) {
            log.error("患者来源任务调用真实 Agent 失败，回退模板分析 - conversationId: {}, correlationId: {}",
                    command.getConversationId(), command.getCorrelationId(), e);
            return analyzeWithTemplateFallback(request, command, "AGENT_EXCEPTION");
        }
    }

    private DoctorAssistantTaskResponse analyzeWithTemplateFallback(
        DoctorAssistantTaskRequest request,
        AgentExecutionCommand command,
        String fallbackReason
    ) {
        String taskId = buildTaskId(request);
        // 只有 sessionService 和 workflowService 都注入成功时，才说明当前环境允许会话持久化与审批编排。
        boolean persistenceEnabled = sessionService != null && workflowService != null;
        String goal = defaultValue(command.getGoal(), "患者来源任务: " + command.getTaskType().name());
        // 这里创建的是“患者任务分析会话”审计壳，用于后续落 history/context；
        // 不是进入 ReAct 模型循环，也不会从这里开始 sub-agent 推理。
        AgentSession session = persistenceEnabled
                ? sessionService.createSession(command.getCustomerId(), command.getDoctorId(), goal)
                : null;
        String agentSessionId = session != null ? String.valueOf(session.getId()) : "patient-agent-" + taskId;
        // 高风险判定来自安全服务，主要识别急症/处方风险信号，后续会影响回复降级和是否转人工。
        boolean highRisk = containsHighRiskSignal(command.getPatientMessage());

        // 当前实现按 taskType 直接走固定模板化分析分支。
        // 这不是 SubAgentCoordinator.select(...) 的动态分派，而是患者任务入口自己的轻量路由。
        StructuredTaskResult structuredResult = switch (command.getTaskType()) {
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

        // 两类情况默认进入人工复核：
        // 1. 患者消息本身命中高风险；
        // 2. 任务类型就是风险分层/升级，本身就要求医生接管。
        boolean needsHumanReview = highRisk || command.getTaskType() == TaskType.RISK_STRATIFICATION_AND_ESCALATION;
        // 先生成一个“理想情况下可返回给患者”的保守回复，再交给安全服务做二次收敛。
        String patientReply = buildFinalPatientReply(command.getTaskType(), structuredResult, needsHumanReview);
        PatientTaskSafetyService.SafetyResult safetyResult = safetyService.sanitize(patientReply, structuredResult, needsHumanReview);

        // blocked 表示安全层认为当前回复仍然不能直接给患者：
        // 可能是急症词、处方/诊断倾向，或者证据不足。此时强制升级为人工复核。
        if (safetyResult.blocked()) {
            needsHumanReview = true;
            patientReply = safetyResult.reply();
            structuredResult.setNeedsAttention(Boolean.TRUE);
            structuredResult.setRiskLevel("HIGH");
            structuredResult.getUncertainties().add("触发患者侧安全拦截: " + safetyResult.category());
        } else {
            // allowed 分支下也要使用 sanitize 后的文案，因为它可能替换掉医生侧术语或附加保守提醒。
            patientReply = safetyResult.reply();
        }

        // 下面这些对象主要用于审计、会话恢复和医生工作台展示，不会驱动模型继续推理。
        AgentContext context = buildContext(request, goal, structuredResult);
        List<Step> history = buildHistory(request, structuredResult, patientReply, needsHumanReview);
        List<Message> messages = List.of(
                Message.user(request.getPatientMessage()),
                Message.assistant(patientReply)
        );

        DoctorAssistantPendingTaskPayload pendingTaskPayload = null;
        // 进入这里说明本次分析不能自动闭环，需要生成待审批任务给医生工作台处理。
        if (needsHumanReview) {
            PendingTask pendingTask = PendingTask.builder()
                    .agentSessionId(agentSessionId)
                    .customerId(command.getCustomerId())
                    .doctorId(command.getDoctorId())
                    .toolName("patient_follow_up_review")
                    .toolDescription("患者来源任务需要医生进一步确认")
                    .inputJson(JSON.toJSONString(Map.of(
                            "taskType", command.getTaskType().name(),
                            "patientMessage", command.getPatientMessage(),
                            "structuredResult", structuredResult)))
                    .reason("高风险/需人工复核的患者来源任务")
                    .status(TaskStatus.PENDING.name())
                    .createdAt(LocalDateTime.now())
                    .expireAt(LocalDateTime.now().plusHours(1))
                    .build();

            // 持久化开启时，把当前上下文、history 和待审批任务一起写入 workflow，
            // 相当于把这次患者任务停在“等待人工确认”的节点。
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

            // 对外返回给医生工作台的是一个轻量 payload，而不是直接暴露内部 PendingTask 实体。
            pendingTaskPayload = resultMapper.toPendingTaskPayload(pendingTask, taskId);
        } else if (persistenceEnabled) {
            // 进入这里说明风险可控且支持持久化，直接把这次分析落成 completed session。
            StructuredFinalAnswer finalAnswer = toFinalAnswer(structuredResult);
            AgentResult auditResult = AgentResult.success(agentSessionId, finalAnswer, history, history.size());
            workflowService.completeSession(
                    agentSessionId,
                    request.getCustomerId(),
                    command.getDoctorId(),
                    goal,
                    history,
                    context,
                    auditResult,
                    history.size(),
                    0L
            );
        }

        // 医生工作台载荷聚合的是“给医生看的运营/审计/复核信息”，
        // 和 patientSafeReply 这种“给患者看的文案”是两套视图。
        DoctorWorkbenchPayload workbenchPayload = resultMapper.toDoctorWorkbenchPayload(
                request,
                command,
                taskId,
                agentSessionId,
                structuredResult,
                needsHumanReview,
                pendingTaskPayload,
                "TEMPLATE_FALLBACK",
                "FALLBACK",
                fallbackReason);

        log.info("患者来源任务分析完成 - taskType: {}, taskId: {}, conversationId: {}, correlationId: {}, sessionId: {}",
                command.getTaskType(), taskId, request.getConversationId(), request.getCorrelationId(), agentSessionId);

        // 最终响应同时返回三层信息：
        // 1. 患者侧安全回复；
        // 2. 结构化分析结果；
        // 3. 医生工作台/待审批所需元数据。
        return DoctorAssistantTaskResponse.builder()
                .agentSessionId(agentSessionId)
                .taskId(taskId)
                .conversationId(request.getConversationId())
                .correlationId(request.getCorrelationId())
                .status(needsHumanReview ? DoctorAssistantTaskStatus.PENDING_APPROVAL : DoctorAssistantTaskStatus.SUCCESS)
                .taskType(command.getTaskType())
                .patientSafeReply(patientReply)
                .structuredResult(structuredResult)
                .doctorWorkbenchPayload(workbenchPayload)
                .executionPath("TEMPLATE_FALLBACK")
                .agentExecutionStatus("FALLBACK")
                .fallbackReason(fallbackReason)
                .needsHumanReview(needsHumanReview)
                .pendingTask(pendingTaskPayload)
                .build();
    }

    private DoctorAssistantTaskResponse buildAgentBackedResponse(
        DoctorAssistantTaskRequest request,
        AgentExecutionCommand command,
        AgentResult agentResult
    ) {
        String taskId = buildTaskId(request);
        boolean highRisk = containsHighRiskSignal(command.getPatientMessage());
        StructuredTaskResult fallbackStructuredResult = buildTemplateStructuredResult(request, command, highRisk);
        StructuredTaskResult structuredResult = resultMapper.toStructuredTaskResult(
                agentResult.getStructuredFinalAnswer(), fallbackStructuredResult, command);
        DoctorAssistantPendingTaskPayload pendingTaskPayload = resultMapper.toPendingTaskPayload(
                agentResult.getPendingTask(), taskId);

        boolean needsHumanReview = agentResult.getStatus() == ResultStatus.PENDING_APPROVAL
                || highRisk
                || command.getTaskType() == TaskType.RISK_STRATIFICATION_AND_ESCALATION;

        String patientReply = buildFinalPatientReply(command.getTaskType(), structuredResult, needsHumanReview);
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

        String agentSessionId = defaultValue(agentResult.getSessionId(), "patient-agent-" + taskId);
        DoctorWorkbenchPayload workbenchPayload = resultMapper.toDoctorWorkbenchPayload(
                request,
                command,
                taskId,
                agentSessionId,
                structuredResult,
                needsHumanReview,
                pendingTaskPayload,
                "AGENT",
                agentResult.getStatus() != null ? agentResult.getStatus().name() : "UNKNOWN",
                null);

        log.info("患者来源任务真实 Agent 执行完成 - taskType: {}, taskId: {}, conversationId: {}, correlationId: {}, sessionId: {}, status: {}",
                command.getTaskType(), taskId, request.getConversationId(), request.getCorrelationId(), agentSessionId,
                agentResult.getStatus());

        return DoctorAssistantTaskResponse.builder()
                .agentSessionId(agentSessionId)
                .taskId(taskId)
                .conversationId(request.getConversationId())
                .correlationId(request.getCorrelationId())
                .status(resultMapper.toTaskStatus(agentResult, needsHumanReview))
                .taskType(command.getTaskType())
                .patientSafeReply(patientReply)
                .structuredResult(structuredResult)
                .doctorWorkbenchPayload(workbenchPayload)
                .executionPath("AGENT")
                .agentExecutionStatus(agentResult.getStatus() != null ? agentResult.getStatus().name() : "UNKNOWN")
                .fallbackReason(null)
                .needsHumanReview(needsHumanReview)
                .pendingTask(pendingTaskPayload)
                .build();
    }

    private String buildFinalPatientReply(
        TaskType taskType,
        StructuredTaskResult structuredResult,
        boolean needsHumanReview
    ) {
        if (patientReplyComposer == null) {
            return fallbackCandidateReply(taskType, structuredResult, needsHumanReview);
        }
        return patientReplyComposer.composeCandidateReply(taskType, structuredResult, needsHumanReview);
    }

    private String fallbackCandidateReply(
        TaskType taskType,
        StructuredTaskResult structuredResult,
        boolean needsHumanReview
    ) {
        return switch (taskType) {
            case PATIENT_FOLLOW_UP_ANALYSIS ->
                    "我先帮你整理了这次随访里最需要关注的信息，建议继续补充近几天的关键数据和变化情况。"
                    + (needsHumanReview ? " 这部分也会同步给医生进一步查看。" : "");
            case RISK_STRATIFICATION_AND_ESCALATION ->
                    "结合你刚才提供的情况，建议尽快联系医生进一步评估；如果症状明显加重，请及时线下就医。";
            case NEXT_FOLLOW_UP_ACTION_RECOMMENDATION ->
                    "下一步建议先补充最近几天的监测结果、症状变化和执行情况，再结合医生意见安排后续随访。";
            default -> structuredResult != null && structuredResult.getSummary() != null
                    ? structuredResult.getSummary()
                    : "已收到你的情况，我们会继续谨慎评估。";
        };
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

    private StructuredTaskResult buildTemplateStructuredResult(
        DoctorAssistantTaskRequest request,
        AgentExecutionCommand command,
        boolean highRisk
    ) {
        return switch (command.getTaskType()) {
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
