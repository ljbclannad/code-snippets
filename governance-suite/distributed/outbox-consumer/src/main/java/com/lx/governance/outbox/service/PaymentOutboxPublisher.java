package com.lx.governance.outbox.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.lx.governance.outbox.repo.OutboxRepo;
import com.lx.governance.outbox.repo.PaymentOutboxRepo;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PaymentOutboxPublisher {
    private final PaymentOutboxRepo repository;
    private final RabbitTemplate rabbitTemplate;
    private final int maxRetries;
    private final int retryDelaySeconds;

    public PaymentOutboxPublisher(PaymentOutboxRepo repository, RabbitTemplate rabbitTemplate,
            @Value("${outbox.max-retries:5}") int maxRetries,
            @Value("${outbox.retry-delay-seconds:10}") int retryDelaySeconds) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.maxRetries = maxRetries;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void pollAndPublish() {
        List<OutboxRepo.Record> records = repository.findNew(50);
        for (OutboxRepo.Record record : records) {
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setMessageId(record.eventId);
                properties.setHeader("eventId", record.eventId);
                CorrelationData correlationData = new CorrelationData(record.eventId);
                rabbitTemplate.send(OutboxPublisher.EXCHANGE, record.eventType,
                        new Message(record.payload.getBytes(StandardCharsets.UTF_8), properties), correlationData);
                CorrelationData.Confirm confirm = correlationData.getFuture().get(3, TimeUnit.SECONDS);
                if (confirm == null || !confirm.isAck()) {
                    throw new IllegalStateException("payment outbox publish was not acknowledged");
                }
                repository.markSent(record.id);
            } catch (Exception exception) {
                repository.markFailed(record.id, record.retryCount + 1, maxRetries, retryDelaySeconds,
                        trimReason(exception.getMessage()));
            }
        }
    }

    private String trimReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown publish error";
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }
}
