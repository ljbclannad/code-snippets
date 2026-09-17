package com.lx.governance.order.export;

import java.time.Instant;

public record FinanceExportRow(String orderNo, String customerId, String productId, Integer quantity, Long amountCents,
        String status, String paymentStatus, String payNo, Instant paymentUpdatedAt, Instant createdAt) {
}
