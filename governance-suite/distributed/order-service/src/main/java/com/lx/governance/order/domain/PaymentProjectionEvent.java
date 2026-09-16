package com.lx.governance.order.domain;

import java.time.Instant;

public record PaymentProjectionEvent(
        String eventId,
        String orderNo,
        String payNo,
        String paymentStatus,
        long paymentVersion,
        Instant paymentUpdatedAt,
        Instant occurredAt,
        String traceId) {
}
