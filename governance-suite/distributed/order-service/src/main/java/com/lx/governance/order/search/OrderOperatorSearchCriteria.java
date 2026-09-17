package com.lx.governance.order.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

public record OrderOperatorSearchCriteria(
        String orderNo,
        String customerSearchToken,
        String productId,
        String status,
        String paymentStatus,
        Instant createdFrom,
        Instant createdTo,
        Long minAmountCents,
        Long maxAmountCents,
        AdminOrderSort sort,
        AdminOrderSortDirection direction,
        int limit) {
    private static final Duration MAX_CREATED_TIME_RANGE = Duration.ofDays(90);

    public static OrderOperatorSearchCriteria create(String orderNo, String customerSearchToken, String productId,
            String status, String paymentStatus, Instant createdFrom, Instant createdTo, Long minAmountCents,
            Long maxAmountCents, int limit) {
        return create(orderNo, customerSearchToken, productId, status, paymentStatus, createdFrom, createdTo, minAmountCents,
                maxAmountCents, null, null, limit);
    }

    public static OrderOperatorSearchCriteria create(String orderNo, String customerSearchToken, String productId,
            String status, String paymentStatus, Instant createdFrom, Instant createdTo, Long minAmountCents,
            Long maxAmountCents, String sort, String direction, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (createdFrom == null || createdTo == null) throw new IllegalArgumentException("createdFrom and createdTo are required");
        if (createdFrom.isAfter(createdTo)) throw new IllegalArgumentException("createdFrom must not be after createdTo");
        if (Duration.between(createdFrom, createdTo).compareTo(MAX_CREATED_TIME_RANGE) > 0) {
            throw new IllegalArgumentException("created time range must not exceed 90 days");
        }
        if (minAmountCents != null && maxAmountCents != null && minAmountCents > maxAmountCents) {
            throw new IllegalArgumentException("minAmountCents must not exceed maxAmountCents");
        }
        return new OrderOperatorSearchCriteria(normalize(orderNo), normalize(customerSearchToken), normalize(productId),
                normalize(status), normalize(paymentStatus), createdFrom, createdTo, minAmountCents, maxAmountCents,
                AdminOrderSort.parse(sort), AdminOrderSortDirection.parse(direction), limit);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String fingerprint() {
        String canonical = canonicalValue(orderNo) + canonicalValue(customerSearchToken) + canonicalValue(productId)
                + canonicalValue(status) + canonicalValue(paymentStatus) + canonicalValue(createdFrom.toString())
                + canonicalValue(createdTo.toString()) + canonicalValue(String.valueOf(minAmountCents))
                + canonicalValue(String.valueOf(maxAmountCents)) + canonicalValue(sort.name()) + canonicalValue(direction.name());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String canonicalValue(String value) {
        String normalized = value == null ? "<null>" : value;
        return normalized.length() + ":" + normalized;
    }
}
