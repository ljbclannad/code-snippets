package io.github.messageguard.core.model;

import java.time.Instant;

public record ConsumeRecord(
        String consumer,
        String group,
        String idempotentKey,
        ConsumeStatus status,
        int retryCount,
        Instant updatedAt
) {
}
