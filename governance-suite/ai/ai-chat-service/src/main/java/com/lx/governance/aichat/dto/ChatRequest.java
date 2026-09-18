package com.lx.governance.aichat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lx.governance.aichat.evaluation.EvaluationBinding;
import com.lx.governance.aichat.evaluation.EvaluationCaptureContext;
import lombok.Data;

/**
 * Chat request DTO
 */
@Data
public class ChatRequest {

    @NotBlank(message = "Group ID cannot be blank")
    private String groupId;

    private String customerId;

    private String userId;

    private String tenantId;

    @NotBlank(message = "Message cannot be blank")
    private String message;

    private String conversationId;

    /**
     * 客户端幂等请求 ID。
     * 同一条消息因网络重试重复发送时应保持不变。
     */
    private String requestId;

    /**
     * 客户端消息时间戳（毫秒）。
     * 用于上下文乱序保护；为空时回退到服务端接收时间。
     */
    private Long messageTimestamp;

    /** 测试批次绑定；服务未启用采集时忽略。 */
    @Valid
    private EvaluationBinding evaluation;

    /** 仅在本次服务调用中使用，不接受客户端输入，也不写入幂等缓存。 */
    @JsonIgnore
    @lombok.ToString.Exclude
    @lombok.EqualsAndHashCode.Exclude
    private EvaluationCaptureContext evaluationCaptureContext;
}
