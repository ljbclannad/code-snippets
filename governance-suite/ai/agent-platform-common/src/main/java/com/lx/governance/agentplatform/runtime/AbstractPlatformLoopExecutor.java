package com.lx.governance.agentplatform.runtime;

import com.lx.governance.agentplatform.model.LoopContext;
import com.lx.governance.agentplatform.model.LoopOutcome;

/**
 * 平台统一循环模板执行器。
 */
public abstract class AbstractPlatformLoopExecutor<S, R> implements PlatformLoopExecutor<S, R> {

    @Override
    public LoopOutcome<S, R> executeLoop(LoopContext<S> loopContext) {
        beforeLoop(loopContext);
        for (int step = loopContext.getStartStep(); step < loopContext.getMaxSteps(); step++) {
            int stepNumber = step + 1;
            beforeStep(loopContext, step, stepNumber);
            try {
                LoopStepDecision<S, R> decision = executeStep(loopContext, step, stepNumber);
                afterStep(loopContext, step, stepNumber, decision);
                if (!decision.isContinueLoop()) {
                    return decision.getOutcome();
                }
            } catch (Exception e) {
                return onStepException(loopContext, step, stepNumber, e);
            }
        }
        return onMaxSteps(loopContext);
    }

    protected void beforeLoop(LoopContext<S> loopContext) {
    }

    protected void beforeStep(LoopContext<S> loopContext, int step, int stepNumber) {
    }

    protected void afterStep(
            LoopContext<S> loopContext,
            int step,
            int stepNumber,
            LoopStepDecision<S, R> decision) {
    }

    protected abstract LoopStepDecision<S, R> executeStep(LoopContext<S> loopContext, int step, int stepNumber)
            throws Exception;

    protected abstract LoopOutcome<S, R> onStepException(
            LoopContext<S> loopContext,
            int step,
            int stepNumber,
            Exception e);

    protected abstract LoopOutcome<S, R> onMaxSteps(LoopContext<S> loopContext);
}
