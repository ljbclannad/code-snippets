package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent执行结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResult {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 执行状态
     */
    private ResultStatus status;

    /**
     * 最终答案
     */
    private String finalAnswer;

    /**
     * 待确认任务（如果需要人工确认）
     */
    private PendingTask pendingTask;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 执行历史
     */
    private List<Step> executionHistory;

    /**
     * 总步数
     */
    private Integer totalSteps;

    /**
     * 创建成功结果
     */
    public static AgentResult success(String sessionId, String answer, List<Step> history, int steps) {
        return AgentResult.builder()
                .sessionId(sessionId)
                .status(ResultStatus.SUCCESS)
                .finalAnswer(answer)
                .executionHistory(history)
                .totalSteps(steps)
                .build();
    }

    /**
     * 创建待确认结果
     */
    public static AgentResult pendingApproval(String sessionId, PendingTask task, List<Step> history, int steps) {
        return AgentResult.builder()
                .sessionId(sessionId)
                .status(ResultStatus.PENDING_APPROVAL)
                .pendingTask(task)
                .executionHistory(history)
                .totalSteps(steps)
                .build();
    }

    /**
     * 创建错误结果
     */
    public static AgentResult error(String sessionId, String errorMessage, List<Step> history, int steps) {
        return AgentResult.builder()
                .sessionId(sessionId)
                .status(ResultStatus.ERROR)
                .errorMessage(errorMessage)
                .executionHistory(history)
                .totalSteps(steps)
                .build();
    }

    /**
     * 创建达到最大步数结果
     */
    public static AgentResult maxStepsReached(String sessionId, List<Step> history, int maxSteps) {
        return AgentResult.builder()
                .sessionId(sessionId)
                .status(ResultStatus.MAX_STEPS)
                .errorMessage("达到最大步数限制: " + maxSteps)
                .executionHistory(history)
                .totalSteps(maxSteps)
                .build();
    }
}
