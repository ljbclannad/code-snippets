package com.lx.governance.inventory.domain;

import java.time.Instant;

public record InventorySnapshot(String productId, int available, Instant observedAt) {
}
