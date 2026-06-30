package com.health.agent.learning.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Agent 运行结果实体。
 */
@Data
@TableName("agent_run_outcome")
public class AgentRunOutcomeEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;
    private String sessionId;
    private String scene;
    private String taskType;
    private String customerId;
    private String doctorId;
    private String finalStatus;
    private String outcomeLabel;
    private Boolean fallbackUsed;
    private Boolean manualReviewRequired;
    private Boolean manualOverride;
    private Boolean doctorAccepted;
    private Boolean doctorRejected;
    private Boolean evidenceSufficient;
    private Integer toolErrorCount;
    private Integer retryCount;
    private Long durationMs;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
