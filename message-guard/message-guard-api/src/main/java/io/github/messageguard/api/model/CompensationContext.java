package io.github.messageguard.api.model;

public record CompensationContext(
        String consumer,
        String group,
        String idempotentKey,
        Object payload
) {
}
