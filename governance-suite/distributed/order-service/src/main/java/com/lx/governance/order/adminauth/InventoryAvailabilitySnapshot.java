package com.lx.governance.order.adminauth;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InventoryAvailabilitySnapshot(String status, Integer available, Instant observedAt) {
    public static InventoryAvailabilitySnapshot available(Integer available, Instant observedAt) {
        return new InventoryAvailabilitySnapshot("AVAILABLE", available, observedAt);
    }

    public static InventoryAvailabilitySnapshot unavailable() {
        return new InventoryAvailabilitySnapshot("UNAVAILABLE", null, null);
    }
}
