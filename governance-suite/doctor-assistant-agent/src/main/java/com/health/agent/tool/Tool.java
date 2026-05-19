package com.health.agent.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tool {

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 风险等级
     */
    private ToolRiskLevel riskLevel;

    /**
     * 输入参数Schema
     */
    private Map<String, Object> inputSchema;

    /**
     * 工具执行器
     */
    private ToolExecutor executor;
}
