package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 执行步骤
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Step {

    /**
     * 步骤类型
     */
    private StepType type;

    /**
     * 内容（Thought或Observation的文本）
     */
    private String content;

    /**
     * 行动决策（Action类型时使用）
     */
    private ActionDecision action;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    /**
     * 创建Thought步骤
     */
    public static Step thought(String thinking) {
        return Step.builder()
                .type(StepType.THOUGHT)
                .content(thinking)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 创建Action步骤
     */
    public static Step action(ActionDecision action) {
        return Step.builder()
                .type(StepType.ACTION)
                .action(action)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * 创建Observation步骤
     */
    public static Step observation(String result) {
        return Step.builder()
                .type(StepType.OBSERVATION)
                .content(result)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
