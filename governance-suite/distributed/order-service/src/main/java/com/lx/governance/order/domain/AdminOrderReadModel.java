package com.lx.governance.order.domain;

import java.time.Instant;

public record AdminOrderReadModel(
        String orderNo,
        String customerId,
        String productId,
        Integer quantity,
        Long amountCents,
        String status,
        long orderVersion,
        String paymentStatus,
        String payNo,
        Instant paymentUpdatedAt,
        long paymentVersion) {
}
