package io.github.messageguard.demo.consumer;

import io.github.messageguard.api.annotation.GuardedConsumer;

public class OrderCancelConsumer {

    @GuardedConsumer(consumer = "order-cancel", group = "demo-group", idempotentKey = "orderId")
    public void onMessage(OrderCancelEvent event) {
    }

    public record OrderCancelEvent(String orderId) {
    }
}
