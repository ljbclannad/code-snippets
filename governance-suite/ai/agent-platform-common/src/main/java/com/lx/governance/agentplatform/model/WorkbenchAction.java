package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 平台工作台动作描述。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchAction {

    private WorkbenchActionType actionType;

    private String label;

    private boolean enabled;

    private boolean requiresComment;

    private Map<String, Object> metadata;
}
