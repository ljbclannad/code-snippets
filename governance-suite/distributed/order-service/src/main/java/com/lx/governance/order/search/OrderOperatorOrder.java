package com.lx.governance.order.search;

import java.time.Instant;

public record OrderOperatorOrder(String orderNo, String customerSearchToken, String productId, Integer quantity,
        Long amountCents, String status, String paymentStatus, Instant createdAt) {
}
