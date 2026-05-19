package com.health.agent.engine;

import com.alibaba.fastjson2.JSON;
import com.health.agent.exception.AgentException;
import com.health.agent.model.*;
import com.health.agent.service.PendingTaskService;
import com.health.agent.tool.Tool;
import com.health.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 行动执行器
 * 根据工具风险等级决定执行策略
 */
@Slf4j
@Service
public class ActionExecutor {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private PendingTaskService pendingTaskService;

    /**
     * 执行 Agent 决定的行动
     */
    public ActionResult execute(ActionDecision action, String customerId, String sessionId) {
        Tool tool = toolRegistry.getTool(action.getToolName());

        if (tool == null) {
            return ActionResult.error("未知工具: " + action.getToolName());
        }

        log.info("执行工具: {}, 风险等级: {}", tool.getName(), tool.getRiskLevel());

        // 根据风险等级决定执行策略
        switch (tool.getRiskLevel()) {
            case LOW:
                // 低风险：直接执行
                return executeDirectly(tool, action.getInput());

            case MEDIUM:
                // 中风险：执行但记录审计日志
                ActionResult result = executeDirectly(tool, action.getInput());
                auditLog(customerId, action, result);
                return result;

            case HIGH:
                // 高风险：创建待确认任务，暂停 Agent
                return createPendingTask(customerId, sessionId, tool, action);

            default:
                return ActionResult.error("未知风险等级");
        }
    }

    /**
     * 直接执行工具
     */
    private ActionResult executeDirectly(Tool tool, Map<String, Object> input) {
        try {
            Object output = tool.getExecutor().execute(input);
            return ActionResult.success(output);
        } catch (Exception e) {
            log.error("工具执行失败: {}", tool.getName(), e);
            return ActionResult.error(e.getMessage());
        }
    }

    /**
     * 创建待确认任务（高风险行动）
     */
    private ActionResult createPendingTask(
        String customerId,
        String sessionId,
        Tool tool,
        ActionDecision action
    ) {
        PendingTask task = PendingTask.builder()
            .agentSessionId(sessionId)
            .customerId(customerId)
            .toolName(tool.getName())
            .toolDescription(tool.getDescription())
            .inputJson(JSON.toJSONString(action.getInput()))
            .reason(action.getReason())
            .status(TaskStatus.PENDING.name())
            .createdAt(LocalDateTime.now())
            .expireAt(LocalDateTime.now().plusHours(24))
            .build();

        pendingTaskService.save(task);

        log.info("创建待确认任务: taskId={}, tool={}, customerId={}", task.getId(), tool.getName(), customerId);

        return ActionResult.pendingApproval(task);
    }

    /**
     * 审计日志
     */
    private void auditLog(String customerId, ActionDecision action, ActionResult result) {
        log.info("审计日志: customerId={}, action={}, success={}",
            customerId, action.getToolName(), result.isSuccess());
        // TODO: 记录到审计日志表
    }
}
