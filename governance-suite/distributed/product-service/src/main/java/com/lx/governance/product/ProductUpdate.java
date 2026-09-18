package com.lx.governance.product;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record ProductUpdate(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String categoryId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String brandId,
        @NotNull @Min(0) @Max(999999999999L) Long priceCents,
        @NotNull Product.Status status,
        @NotNull @Min(1) @Max(Long.MAX_VALUE - 1) Long expectedVersion) { }
