package com.health.agent.tool;

/**
 * 工具风险等级
 */
public enum ToolRiskLevel {
    /**
     * 低风险：查询、分析 - 直接执行
     */
    LOW,

    /**
     * 中风险：发消息、创建任务 - 直接执行但记录
     */
    MEDIUM,

    /**
     * 高风险：修改处方、诊断建议 - 需要医生确认
     */
    HIGH
}
