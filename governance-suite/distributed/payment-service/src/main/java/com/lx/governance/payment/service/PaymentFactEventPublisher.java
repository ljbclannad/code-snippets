package com.lx.governance.payment.service;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.payment.config.RabbitConfig;
import com.lx.governance.payment.domain.PaymentDto;
import com.lx.governance.payment.domain.PaymentProjectionEvent;
import com.lx.governance.payment.repo.PaymentOutboxRepository;
import org.springframework.stereotype.Component;

@Component
public class PaymentFactEventPublisher {
    private final PaymentOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public PaymentFactEventPublisher(PaymentOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void publish(PaymentDto payment) {
        if (payment == null || payment.getVersion() == null || payment.getUpdatedAt() == null) {
            throw new IllegalArgumentException("payment snapshot with version and updatedAt is required");
        }
        String eventId = UUID.randomUUID().toString();
        PaymentProjectionEvent event = new PaymentProjectionEvent(eventId, payment.getOrderNo(), payment.getPayNo(),
                payment.getStatus(), payment.getVersion(), payment.getUpdatedAt(), Instant.now(), Trace.getOrCreate());
        try {
            outboxRepository.insert(eventId, payment.getOrderNo(), RabbitConfig.RK_PAYMENT_STATUS_CHANGED,
                    objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize payment fact event", exception);
        }
    }
}
