package com.lx.governance.outbox.service;

import com.lx.governance.outbox.repo.OutboxRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    public static final String EXCHANGE = "biz.events";

    private final OutboxRepo repo;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisher(OutboxRepo repo, RabbitTemplate rabbitTemplate) {
        this.repo = repo;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void pollAndPublish() {
        List<OutboxRepo.Record> batch = repo.findNew(50);
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxRepo.Record r : batch) {
            try {
                MessageProperties props = new MessageProperties();
                props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                props.setMessageId(r.eventId);
                props.setHeader("eventId", r.eventId);
                Message msg = new Message(r.payload.getBytes(StandardCharsets.UTF_8), props);

                rabbitTemplate.send(EXCHANGE, r.eventType, msg);
                repo.markSent(r.id);
                log.info("Published outbox id={} eventType={} eventId={}", r.id, r.eventType, r.eventId);
            } catch (Exception e) {
                repo.markFailed(r.id, e.getMessage());
                log.error("Failed publishing outbox id={} eventId={}", r.id, r.eventId, e);
            }
        }
    }
}
