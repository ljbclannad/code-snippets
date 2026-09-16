package com.lx.governance.order.domain;

import java.time.Instant;

public record OrderProjectionEvent(
        String eventId,
        String orderNo,
        String customerId,
        String productId,
        int quantity,
        int amountCents,
        String status,
        long orderVersion,
        Instant occurredAt,
        String traceId) {
}
