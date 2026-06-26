package com.lx.governance.nacossimulator.dto;

import jakarta.validation.constraints.NotBlank;

public record HeartbeatRequest(
        @NotBlank String serviceName,
        @NotBlank String instanceId) {
}
