package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.LoopOutcome;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 单步循环执行后的控制决策。
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LoopStepDecision<S, R> {

    private final boolean continueLoop;

    private final LoopOutcome<S, R> outcome;

    public static <S, R> LoopStepDecision<S, R> continueLoop() {
        return new LoopStepDecision<>(true, null);
    }

    public static <S, R> LoopStepDecision<S, R> terminate(LoopOutcome<S, R> outcome) {
        return new LoopStepDecision<>(false, outcome);
    }
}
