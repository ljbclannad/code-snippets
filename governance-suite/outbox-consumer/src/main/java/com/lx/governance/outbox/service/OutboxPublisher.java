package com.lx.governance.outbox.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lx.governance.outbox.repo.OutboxRepo;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    public static final String EXCHANGE = "biz.events";

    private final OutboxRepo repo;
    private final RabbitTemplate rabbitTemplate;
    private final int maxRetries;
    private final int retryDelaySeconds;
    private final boolean failPublish;

    public OutboxPublisher(OutboxRepo repo,
                           RabbitTemplate rabbitTemplate,
                           @Value("${outbox.max-retries:5}") int maxRetries,
                           @Value("${outbox.retry-delay-seconds:10}") int retryDelaySeconds,
                           @Value("${lab.fail.publish:false}") boolean failPublish) {
        this.repo = repo;
        this.rabbitTemplate = rabbitTemplate;
        this.maxRetries = maxRetries;
        this.retryDelaySeconds = retryDelaySeconds;
        this.failPublish = failPublish;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void pollAndPublish() {
        List<OutboxRepo.Record> batch = repo.findNew(50);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxRepo.Record r : batch) {
            try {
                if (failPublish) {
                    throw new IllegalStateException("lab injected failure: outbox publish");
                }

                MessageProperties props = new MessageProperties();
                props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                props.setMessageId(r.eventId);
                props.setHeader("eventId", r.eventId);
                Message msg = new Message(r.payload.getBytes(StandardCharsets.UTF_8), props);

                rabbitTemplate.send(EXCHANGE, r.eventType, msg);
                repo.markSent(r.id);
                log.info("Published outbox id={} eventType={} eventId={} retryCount={}",
                        r.id, r.eventType, r.eventId, r.retryCount);
            } catch (Exception e) {
                int nextRetryCount = r.retryCount + 1;
                repo.markFailed(r.id, nextRetryCount, maxRetries, retryDelaySeconds, trimReason(e.getMessage()));
                log.error("Failed publishing outbox id={} eventId={} retryCount={}/{}",
                        r.id, r.eventId, nextRetryCount, maxRetries, e);
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
