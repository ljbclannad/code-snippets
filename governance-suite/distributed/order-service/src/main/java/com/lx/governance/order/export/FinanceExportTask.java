package com.lx.governance.order.export;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lx.governance.order.search.AdminOrderSort;
import com.lx.governance.order.search.AdminOrderSortDirection;

public record FinanceExportTask(String id, long ownerUserId, FinanceExportStatus status, String criteriaFingerprint,
        @JsonIgnore String criteriaSnapshot, @JsonIgnore String fieldSet, @JsonIgnore String roleSnapshot,
        @JsonIgnore String searchScope, @JsonIgnore Instant readModelWatermark,
        AdminOrderSort sort, AdminOrderSortDirection direction, int rowCount, String fileKey, String failureSummary,
        Instant createdAt, Instant completedAt, Instant expiresAt) {
    public boolean isExpired(Instant now) {
        return status == FinanceExportStatus.EXPIRED || (expiresAt != null && !now.isBefore(expiresAt));
    }
}
