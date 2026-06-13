package com.health.agent.tool;

import java.util.Map;

/**
 * 工具执行器接口
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * 执行工具
     *
     * @param input 输入参数
     * @return 执行结果
     */
    Object execute(Map<String, Object> input);
}
