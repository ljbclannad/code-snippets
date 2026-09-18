package com.lx.governance.outbox.service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import com.lx.governance.outbox.repo.OutboxRepo;
import com.lx.governance.outbox.repo.ProductOutboxRepo;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/** 通过 broker confirm 确认商品事件投递，数据库更新失败允许再次发送。 */
public class ProductOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(ProductOutboxPublisher.class);
    private final ProductOutboxRepo repository;
    private final RabbitTemplate rabbit;
    private final int maxRetries;
    private final int retryDelaySeconds;
    private final MeterRegistry meters;

    public ProductOutboxPublisher(ProductOutboxRepo repository, RabbitTemplate rabbit, int maxRetries,
            int retryDelaySeconds, MeterRegistry meters) {
        this.repository = repository;
        this.rabbit = rabbit;
        this.maxRetries = maxRetries;
        this.retryDelaySeconds = retryDelaySeconds;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void pollAndPublish() {
        for (OutboxRepo.Record record : repository.findNew(50)) {
            try {
                MessageProperties properties = new MessageProperties();
                properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                properties.setMessageId(record.eventId);
                properties.setHeader("eventId", record.eventId);
                CorrelationData correlation = new CorrelationData(record.eventId);
                rabbit.send("biz.events", "product.changed",
                        new Message(record.payload.getBytes(StandardCharsets.UTF_8), properties), correlation);
                CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
                if (!confirm.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("商品事件未获 broker 确认或无匹配队列");
                }
                repository.markSent(record.id);
                meters.counter("outbox.product.publish.total", "status", "success").increment();
            } catch (Exception exception) {
                int retries = record.retryCount + 1;
                String reason = exception.getMessage();
                if (reason == null || reason.isBlank()) {
                    reason = "商品事件投递失败";
                }
                repository.markFailed(record.id, retries, maxRetries, retryDelaySeconds,
                        reason.substring(0, Math.min(reason.length(), 500)));
                meters.counter("outbox.product.publish.total", "status", "failure").increment();
                meters.counter(retries >= maxRetries ? "outbox.product.dead.total" : "outbox.product.retry.total").increment();
                log.warn("商品 Outbox 投递失败 eventId={} retryCount={} cause={}", record.eventId, retries,
                        exception.getClass().getSimpleName());
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
