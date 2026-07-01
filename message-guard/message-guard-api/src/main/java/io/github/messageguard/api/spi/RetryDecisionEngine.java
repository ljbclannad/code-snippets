package io.github.messageguard.api.spi;

import io.github.messageguard.api.model.RetryAction;
import io.github.messageguard.api.model.RetryContext;

public interface RetryDecisionEngine {

    RetryAction decide(Throwable throwable, RetryContext context);
}
