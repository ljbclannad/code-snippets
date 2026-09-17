package com.lx.governance.order.rebuild;

import java.time.Instant;

public record ProjectionRebuildTask(String id, long operatorUserId, String status, int phase, long orderWatermark0,
        long orderWatermark1, long paymentWatermark0, long paymentWatermark1, long orderCursor0, long orderCursor1,
        long paymentCursor0, long paymentCursor1, long processedCount, long successCount, long failureCount,
        String failureReason, Instant createdAt, Instant updatedAt) {
    public long watermark() {
        return switch (phase) {
            case 0 -> orderWatermark0;
            case 1 -> orderWatermark1;
            case 2 -> paymentWatermark0;
            case 3 -> paymentWatermark1;
            default -> 0;
        };
    }

    public long cursor() {
        return switch (phase) {
            case 0 -> orderCursor0;
            case 1 -> orderCursor1;
            case 2 -> paymentCursor0;
            case 3 -> paymentCursor1;
            default -> 0;
        };
    }
}
