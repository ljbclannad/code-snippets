package com.lx.governance.order.adminauth;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminOrderSearchItem(
        String orderNo,
        String customerId,
        String productId,
        Integer quantity,
        Long amountCents,
        String status,
        String paymentStatus,
        String payNo,
        Instant paymentUpdatedAt,
        Instant createdAt) {
}
