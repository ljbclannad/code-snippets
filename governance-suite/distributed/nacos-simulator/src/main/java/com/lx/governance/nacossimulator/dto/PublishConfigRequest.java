package com.lx.governance.nacossimulator.dto;

import jakarta.validation.constraints.NotBlank;

public record PublishConfigRequest(
        @NotBlank String dataId,
        @NotBlank String group,
        @NotBlank String content) {
}
