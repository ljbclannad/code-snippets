package com.lx.governance.product;

import java.time.Instant;

public record Product(String productId, String title, String categoryId, String brandId,
                      long priceCents, Status status, long version, Instant updatedAt) {
    public enum Status { ON_SALE, OFF_SALE }
}
