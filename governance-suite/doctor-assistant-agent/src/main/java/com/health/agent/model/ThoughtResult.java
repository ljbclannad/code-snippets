package com.health.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 思考结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ThoughtResult {

    /**
     * 思考内容
     */
    private String thinking;

    /**
     * 是否完成
     */
    private boolean finished;

    /**
     * 最终答案（finished=true时使用）
     */
    private String finalAnswer;

    /**
     * 行动决策（finished=false时使用）
     */
    private ActionDecision action;

    /**
     * 创建完成结果
     */
    public static ThoughtResult finished(String thinking, String answer) {
        return ThoughtResult.builder()
                .thinking(thinking)
                .finished(true)
                .finalAnswer(answer)
                .build();
    }

    /**
     * 创建继续执行结果
     */
    public static ThoughtResult continueWith(String thinking, ActionDecision action) {
        return ThoughtResult.builder()
                .thinking(thinking)
                .finished(false)
                .action(action)
                .build();
    }
}
