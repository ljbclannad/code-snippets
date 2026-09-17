package com.lx.governance.order.adminauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.client.InventorySnapshotClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class InventorySnapshotEnricher {
    private static final int MAX_PRODUCT_IDS = 100;

    private final InventorySnapshotClient inventorySnapshotClient;
    private final MeterRegistry meters;

    public InventorySnapshotEnricher(InventorySnapshotClient inventorySnapshotClient, MeterRegistry meters) {
        this.inventorySnapshotClient = inventorySnapshotClient;
        this.meters = meters;
    }

    public List<AdminOrderSearchItem> enrich(List<AdminOrderSearchItem> items) {
        List<String> productIds = items.stream().map(AdminOrderSearchItem::productId).filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        if (productIds.isEmpty()) return items;
        if (productIds.size() > MAX_PRODUCT_IDS) {
            degraded("limit");
            return unavailable(items);
        }
        try {
            ApiResponse<List<InventorySnapshotClient.InventorySnapshotResponse>> response = inventorySnapshotClient.snapshots(
                    new InventorySnapshotClient.InventorySnapshotRequest(productIds));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                degraded("upstream");
                return unavailable(items);
            }
            Map<String, InventoryAvailabilitySnapshot> snapshots = snapshotsByProductId(response.getData());
            if (snapshots.size() < productIds.size()) degraded("partial");
            return items.stream().map(item -> withSnapshot(item, snapshots.getOrDefault(item.productId(),
                    InventoryAvailabilitySnapshot.unavailable()))).toList();
        } catch (RuntimeException exception) {
            degraded("exception");
            return unavailable(items);
        }
    }

    private void degraded(String reason) {
        meters.counter("admin.order.inventory.degraded.total", "reason", reason).increment();
    }

    private Map<String, InventoryAvailabilitySnapshot> snapshotsByProductId(
            List<InventorySnapshotClient.InventorySnapshotResponse> responses) {
        Map<String, InventoryAvailabilitySnapshot> snapshots = new LinkedHashMap<>();
        for (InventorySnapshotClient.InventorySnapshotResponse response : responses) {
            if (response.productId() == null || response.available() == null || response.observedAt() == null) continue;
            snapshots.putIfAbsent(response.productId(), InventoryAvailabilitySnapshot.available(response.available(), response.observedAt()));
        }
        return snapshots;
    }

    private List<AdminOrderSearchItem> unavailable(List<AdminOrderSearchItem> items) {
        return items.stream().map(item -> withSnapshot(item, InventoryAvailabilitySnapshot.unavailable())).toList();
    }

    private AdminOrderSearchItem withSnapshot(AdminOrderSearchItem item, InventoryAvailabilitySnapshot snapshot) {
        return new AdminOrderSearchItem(item.orderNo(), item.customerId(), item.productId(), item.quantity(), item.amountCents(),
                item.status(), item.paymentStatus(), item.payNo(), item.paymentUpdatedAt(), item.createdAt(), snapshot);
    }
}
