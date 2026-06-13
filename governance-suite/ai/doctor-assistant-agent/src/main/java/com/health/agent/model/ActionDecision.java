package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 行动决策
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionDecision {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 输入参数
     */
    private Map<String, Object> input;

    /**
     * 执行原因
     */
    private String reason;
}
