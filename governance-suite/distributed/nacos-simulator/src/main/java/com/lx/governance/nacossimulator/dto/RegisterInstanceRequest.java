package com.lx.governance.nacossimulator.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record RegisterInstanceRequest(
        @NotBlank String serviceName,
        @NotBlank String ip,
        @Min(1) int port,
        Map<String, String> metadata,
        Long ttlMillis) {
}
