package io.github.messageguard.api.model;

import java.time.Instant;
import java.util.Map;

public record MessageContext(
        String consumer,
        String group,
        String messageId,
        String adapter,
        Instant receivedAt,
        Map<String, Object> headers
) {
}
