package com.lx.governance.nacossimulator.dto;

public record AdminStateResponse(
        int serviceCount,
        int healthyInstanceCount,
        int pendingLongPollCount) {
}
