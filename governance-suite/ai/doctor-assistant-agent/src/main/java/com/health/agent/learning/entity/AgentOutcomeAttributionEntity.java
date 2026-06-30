package com.health.agent.learning.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Agent 结果归因实体。
 */
@Data
@TableName("agent_outcome_attribution")
public class AgentOutcomeAttributionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;
    private String sessionId;
    private String scene;
    private String taskType;
    private String primaryCause;
    private String secondaryCausesJson;
    private Double confidence;
    private String evidenceSummaryJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
