package com.lx.governance.inventory.domain;

import java.util.List;

public record InventorySnapshotBatchRequest(List<String> productIds) {
}
