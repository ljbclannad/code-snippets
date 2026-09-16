package com.lx.governance.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.domain.OrderDto;
import com.lx.governance.order.domain.OrderProjectionEvent;
import com.lx.governance.order.repo.OutboxRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class OrderFactEventPublisher {
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OrderFactEventPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void publish(OrderDto order, String eventType) {
        if (order == null || order.getVersion() == null) {
            throw new IllegalArgumentException("order snapshot with version is required");
        }
        String eventId = UUID.randomUUID().toString();
        OrderProjectionEvent event = new OrderProjectionEvent(eventId, order.getOrderNo(), order.getCustomerId(),
                order.getProductId(), order.getQuantity(), order.getAmountCents(), order.getStatus(), order.getVersion(),
                Instant.now(), Trace.getOrCreate());
        try {
            outboxRepository.insert(eventId, "ORDER", order.getOrderNo(), eventType, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize order fact event", e);
        }
    }
}
