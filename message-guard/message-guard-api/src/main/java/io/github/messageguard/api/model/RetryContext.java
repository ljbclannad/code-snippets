package io.github.messageguard.api.model;

public record RetryContext(
        int currentRetryCount,
        int maxRetryCount,
        boolean idempotentSafe
) {

    public boolean canRetry() {
        return idempotentSafe && currentRetryCount < maxRetryCount;
    }
}
