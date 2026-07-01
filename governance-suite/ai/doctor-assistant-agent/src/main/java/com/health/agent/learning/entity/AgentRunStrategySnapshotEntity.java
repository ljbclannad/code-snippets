package com.health.agent.learning.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Agent 运行策略快照实体。
 */
@Data
@TableName("agent_run_strategy_snapshot")
public class AgentRunStrategySnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;
    private String sessionId;
    private String scene;
    private String taskType;
    private String promptVersion;
    private String selectorVersion;
    private String selectedAgentIdsJson;
    private String selectionMode;
    private String toolCatalogVersion;
    private String toolSequenceJson;
    private String memoryProfileJson;
    private Integer memoryRetrievalCount;
    private Boolean memoryRerankApplied;
    private String degradationLevel;
    private Boolean parallelEnabled;
    private Boolean fallbackAllowed;
    private Integer timeoutMillis;
    private Boolean patientVisibleOnly;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
