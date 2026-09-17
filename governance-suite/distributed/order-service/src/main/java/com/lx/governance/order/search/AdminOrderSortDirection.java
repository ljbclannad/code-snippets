package com.lx.governance.order.search;

public enum AdminOrderSortDirection {
    ASC,
    DESC;

    public static AdminOrderSortDirection parse(String value) {
        if (value == null || value.isBlank()) return DESC;
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported sort direction");
        }
    }
}
