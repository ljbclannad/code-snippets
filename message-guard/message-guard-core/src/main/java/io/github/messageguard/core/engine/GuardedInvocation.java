package io.github.messageguard.core.engine;

import io.github.messageguard.api.model.RetryAction;
import io.github.messageguard.api.model.RetryContext;

public class GuardedInvocation {

    private final DefaultRetryDecisionEngine retryDecisionEngine;

    public GuardedInvocation(DefaultRetryDecisionEngine retryDecisionEngine) {
        this.retryDecisionEngine = retryDecisionEngine;
    }

    public RetryAction onFailure(Throwable throwable, int retries, int maxRetries) {
        return retryDecisionEngine.decide(throwable, new RetryContext(retries, maxRetries, true));
    }
}
