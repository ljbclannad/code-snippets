package io.github.messageguard.ops.api;

import io.github.messageguard.core.model.ConsumeStatus;

public record ConsumeRecordView(
        String consumer,
        String group,
        String idempotentKey,
        ConsumeStatus status,
        int retryCount
) {
}
