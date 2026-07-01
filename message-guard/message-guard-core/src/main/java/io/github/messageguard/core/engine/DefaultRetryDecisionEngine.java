package io.github.messageguard.core.engine;

import io.github.messageguard.api.spi.RetryDecisionEngine;
import io.github.messageguard.api.model.RetryAction;
import io.github.messageguard.api.model.RetryContext;

import java.util.Objects;
import java.util.Set;

public class DefaultRetryDecisionEngine implements RetryDecisionEngine {

    private final Set<Class<? extends Throwable>> retryOn;
    private final Set<Class<? extends Throwable>> noRetryOn;

    public DefaultRetryDecisionEngine(
            Set<Class<? extends Throwable>> retryOn,
            Set<Class<? extends Throwable>> noRetryOn
    ) {
        this.retryOn = Objects.requireNonNull(retryOn);
        this.noRetryOn = Objects.requireNonNull(noRetryOn);
    }

    @Override
    public RetryAction decide(Throwable throwable, RetryContext context) {
        if (matches(throwable, noRetryOn)) {
            return RetryAction.DEAD_LETTER;
        }
        if (matches(throwable, retryOn) && context.canRetry()) {
            return RetryAction.DELAY_RETRY;
        }
        return RetryAction.DEAD_LETTER;
    }

    private boolean matches(Throwable throwable, Set<Class<? extends Throwable>> candidates) {
        return candidates.stream().anyMatch(candidate -> candidate.isAssignableFrom(throwable.getClass()));
    }
}
