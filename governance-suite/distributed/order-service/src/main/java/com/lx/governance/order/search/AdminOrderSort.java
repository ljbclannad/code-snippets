package com.lx.governance.order.search;

public enum AdminOrderSort {
    CREATED_AT("created_at"),
    AMOUNT_CENTS("amount_cents"),
    PAYMENT_UPDATED_AT("payment_updated_at");

    private final String column;

    AdminOrderSort(String column) {
        this.column = column;
    }

    public String column() {
        return column;
    }

    public static AdminOrderSort parse(String value) {
        if (value == null || value.isBlank()) return CREATED_AT;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported sort");
        }
    }
}
