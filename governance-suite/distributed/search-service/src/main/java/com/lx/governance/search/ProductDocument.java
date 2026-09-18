package com.lx.governance.search;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;

public record ProductDocument(String productId, String title, String categoryId, String brandId,
                              Long priceCents, String status, Long version,
                              @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt) {
    public ProductDocument {
        if (!validId(productId) || !validId(categoryId) || !validId(brandId)
                || title == null || title.isBlank() || title.length() > 200
                || priceCents == null || priceCents < 0 || priceCents > 999999999999L
                || !("ON_SALE".equals(status) || "OFF_SALE".equals(status))
                || version == null || version < 1 || updatedAt == null) {
            throw new IllegalArgumentException("商品快照缺少有效字段");
        }
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }
}
