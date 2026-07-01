package com.lx.governance.agentplatform.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台统一工作台待办摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchPendingTask {

    private String approvalTaskId;

    private String runId;

    private String status;

    private String title;

    private String reason;

    private Long expireAt;
}
