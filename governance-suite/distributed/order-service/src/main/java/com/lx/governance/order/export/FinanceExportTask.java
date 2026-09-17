package com.lx.governance.order.export;

import java.time.Instant;

import com.lx.governance.order.search.AdminOrderSort;
import com.lx.governance.order.search.AdminOrderSortDirection;

public record FinanceExportTask(String id, long ownerUserId, FinanceExportStatus status, String criteriaFingerprint,
        AdminOrderSort sort, AdminOrderSortDirection direction, int rowCount, String fileKey, String failureSummary,
        Instant createdAt, Instant completedAt, Instant expiresAt) {
    public boolean isExpired(Instant now) {
        return status == FinanceExportStatus.EXPIRED || (expiresAt != null && !now.isBefore(expiresAt));
    }
}
