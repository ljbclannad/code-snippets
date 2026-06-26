package com.lx.governance.nacossimulator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ConfigListenerRequest(
        @NotBlank String clientId,
        @NotEmpty List<ConfigProbe> probes) {
}
