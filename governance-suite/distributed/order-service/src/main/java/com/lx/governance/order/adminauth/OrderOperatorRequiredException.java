package com.lx.governance.order.adminauth;

public class OrderOperatorRequiredException extends RuntimeException {
    public OrderOperatorRequiredException() {
        super("ORDER_OPERATOR role is required");
    }
}
