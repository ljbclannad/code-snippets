package com.example.outbox.job;

import com.example.outbox.mapper.OutboxMapper;
import com.example.outbox.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPublisherJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);

    private final OutboxMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    public OutboxPublisherJob(OutboxMapper outboxMapper, RabbitTemplate rabbitTemplate) {
        this.outboxMapper = outboxMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    @Transactional(rollbackFor = Exception.class)
    public void pollAndPublish() {
        List<OutboxEvent> events = outboxMapper.findNew(50);
        if (events.isEmpty()) {
            return;
        }
        for (OutboxEvent e : events) {
            String routingKey = mapRoutingKey(e.getEventType());
            rabbitTemplate.convertAndSend("biz.events", routingKey, e.getPayloadJson());
            outboxMapper.markSent(e.getId());
            log.info("outbox published id={} type={} rk={}", e.getId(), e.getEventType(), routingKey);
        }
    }

    private String mapRoutingKey(String eventType) {
        if ("OrderCreated".equals(eventType)) {
            return "order.created";
        }
        return "event." + eventType;
    }
}
