package com.lx.governance.aichat.service;

import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.TaskTriggerDecision;
import com.lx.governance.aichat.dto.TaskType;
import com.lx.governance.aichat.metrics.AiChatMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

@Service
public class HighRiskFollowUpChat {
    private static final String SAFETY = "若现在有紧急不适，请立即就医或联系当地急救服务，不要等待线上回复。";
    // 整句匹配，带有新增症状或用药问题的消息继续进入临床对话路由。
    private static final Pattern STATUS_QUESTION = Pattern.compile(
            "(?:请问)?(?:刚才|上次|之前)?(?:那条|这条|那个|这个)?(?:的)?(?:随访提醒|随访|提醒|复核任务|任务|申请)?"
            + "(?:医生(?:已经)?(?:看过|看|处理|回复)(?:了没有|了吗|过了吗|没有)|(?:复核|审核|审批)(?:通过了吗|了吗|结果出来了吗|结果|进度|状态)"
            + "|(?:处理|发送|送达)了吗|(?:现在)?(?:什么进度|什么状态|进度如何|进度|状态)|(?:任务|复核|随访提醒)(?:怎么样了|有结果了吗)|到哪一步了)[？?。！!]*");

    private final RestClient client;
    private final DoctorAssistantAgentEndpoint endpoint;
    private final AiChatMetrics metrics;
    private final AgentDispatchPolicy dispatch;
    private final boolean enabled;
    private final String serviceToken;

    public HighRiskFollowUpChat(@Qualifier("doctorAssistantAgentRestClient") RestClient client,
            DoctorAssistantAgentEndpoint endpoint, AiChatMetrics metrics, AgentDispatchPolicy dispatch,
            @Value("${ai-chat.follow-up-reminder.enabled:false}") boolean enabled,
            @Value("${ai-chat.follow-up-reminder.service-token:}") String serviceToken) {
        this.client = client;
        this.endpoint = endpoint;
        this.metrics = metrics;
        this.dispatch = dispatch;
        this.enabled = enabled;
        this.serviceToken = serviceToken;
    }

    public boolean handles(String message) {
        return isStatusQuestion(message) || enabled && TaskTriggerRouter.hasHighRiskMedicalSignal(message);
    }

    private boolean isStatusQuestion(String message) {
        return message != null && STATUS_QUESTION.matcher(message.strip()).matches();
    }

    public String conversationId(ChatRequest request) {
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            // 首轮响应丢失时，客户端仍可用同一请求编号找回会话。
            String seed = request.getCustomerId() + "\n" + request.getRequestId();
            UUID id = request.getRequestId() == null ? UUID.randomUUID()
                    : UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
            request.setConversationId(id.toString());
        }
        return request.getConversationId();
    }

    record Receipt(Long taskId, String status) {}

    public String reply(ChatRequest request) {
        if (isStatusQuestion(request.getMessage())) {
            return progress(request);
        }
        if (!request.isVerifiedPatient() || request.getRequestId() == null || request.getRequestId().isBlank()
                || serviceToken.isBlank()) {
            return unavailable();
        }
        var risk = TaskTriggerDecision.builder().taskType(TaskType.RISK_STRATIFICATION_AND_ESCALATION).build();
        if (!dispatch.evaluate(request, null, risk).invokeAgent()) {
            return unavailable();
        }
        try {
            Receipt receipt = client.post().uri(endpoint.resolve("/internal/patient-tasks/high-risk-follow-up"))
                    .header("X-Follow-Up-Token", serviceToken)
                    .body(Map.of("customerId", request.getCustomerId(), "conversationId", request.getConversationId(),
                            "requestId", request.getRequestId(), "patientMessage", request.getMessage()))
                    .retrieve().body(Receipt.class);
            if (receipt == null || receipt.taskId() == null || receipt.status() == null) {
                return unavailable();
            }
            return switch (receipt.status()) {
                case "PENDING" -> SAFETY + " 已为你登记随访复核，等待责任医生处理。";
                case "APPROVED" -> SAFETY + " 医生已确认随访提醒，可在站内收件箱查看。";
                case "REJECTED" -> SAFETY + " 医生已完成复核，本次未发送随访提醒。";
                default -> unavailable();
            };
        } catch (RestClientException ex) {
            return unavailable();
        }
    }

    record Progress(String status, boolean delivered) {}

    private String progress(ChatRequest request) {
        if (!request.isVerifiedPatient()) {
            return "请登录患者账号后，在原会话查询复核进度。";
        }
        String unknown = "暂时无法确认本次会话的复核进度，请稍后再查或直接联系医生。";
        if (serviceToken.isBlank()) {
            return unknown;
        }
        try {
            var uri = UriComponentsBuilder.fromUri(endpoint.resolve("/internal/patient-tasks/review-status"))
                    .queryParam("customerId", "{patient}").queryParam("conversationId", "{conversation}")
                    .build(request.getCustomerId(), request.getConversationId());
            Progress result = client.get().uri(uri)
                    .header("X-Follow-Up-Token", serviceToken).retrieve().body(Progress.class);
            if (result == null || result.status() == null) {
                return unknown;
            }
            return switch (result.status()) {
                case "PENDING" -> "本次会话最近一条任务仍在等待医生复核。";
                case "APPROVED" -> result.delivered() ? "医生已批准，随访提醒已送达站内收件箱。" : "医生已批准本次复核任务。";
                case "REJECTED" -> "医生已完成复核，本次任务未获批准。";
                case "EXPIRED" -> "本次复核任务已过期，请直接联系医生。";
                case "NONE" -> "本次会话尚未查到复核任务。";
                default -> unknown;
            };
        } catch (RestClientException ex) {
            metrics.recordAgentFallback("review_status_unavailable");
            return unknown;
        }
    }

    String unavailable(ChatRequest request) {
        return isStatusQuestion(request.getMessage()) ? "暂时无法查询复核进度，请稍后再试。" : unavailable();
    }

    private String unavailable() {
        metrics.recordAgentFallback("high_risk_follow_up_unconfirmed");
        return SAFETY + " 当前无法确认随访复核已登记，请直接联系医生。";
    }
}
