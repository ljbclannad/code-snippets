package com.lx.governance.order.client;

import java.time.Instant;
import java.util.List;

import com.lx.governance.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "inventory-service", contextId = "inventorySnapshotClient", fallback = InventorySnapshotClient.Fallback.class)
public interface InventorySnapshotClient {
    @PostMapping("/snapshots")
    ApiResponse<List<InventorySnapshotResponse>> snapshots(@RequestBody InventorySnapshotRequest request);

    record InventorySnapshotRequest(List<String> productIds) {
    }

    record InventorySnapshotResponse(String productId, Integer available, Instant observedAt) {
    }

    @Component
    class Fallback implements InventorySnapshotClient {
        @Override
        public ApiResponse<List<InventorySnapshotResponse>> snapshots(InventorySnapshotRequest request) {
            return ApiResponse.fail("INVENTORY_UNAVAILABLE", "inventory-service fallback", null);
        }
    }
}
