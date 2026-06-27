package io.github.messageguard.core.engine;

import io.github.messageguard.api.model.RetryAction;
import io.github.messageguard.api.model.RetryContext;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRetryDecisionEngineTest {

    @Test
    void shouldRetryTransientExceptionWhenAttemptsRemain() {
        DefaultRetryDecisionEngine engine = new DefaultRetryDecisionEngine(
                Set.of(TimeoutException.class),
                Set.of(IllegalArgumentException.class)
        );

        RetryAction action = engine.decide(new TimeoutException("timeout"), new RetryContext(1, 3, true));

        assertThat(action).isEqualTo(RetryAction.DELAY_RETRY);
    }

    @Test
    void shouldDeadLetterNonRetryableException() {
        DefaultRetryDecisionEngine engine = new DefaultRetryDecisionEngine(
                Set.of(TimeoutException.class),
                Set.of(IllegalArgumentException.class)
        );

        RetryAction action = engine.decide(new IllegalArgumentException("biz"), new RetryContext(1, 3, true));

        assertThat(action).isEqualTo(RetryAction.DEAD_LETTER);
    }
}
