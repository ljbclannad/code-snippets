package com.lx.governance.nacossimulator.dto;

import jakarta.validation.constraints.NotBlank;

public record DeregisterRequest(
        @NotBlank String serviceName,
        @NotBlank String instanceId) {
}
