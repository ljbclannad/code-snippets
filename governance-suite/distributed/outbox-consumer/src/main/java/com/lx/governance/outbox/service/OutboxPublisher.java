package com.lx.governance.outbox.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.logstash.logback.argument.StructuredArguments.kv;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lx.governance.outbox.repo.OutboxRepo;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    public static final String EXCHANGE = "biz.events";

    private final OutboxRepo repo;
    private final RabbitTemplate rabbitTemplate;
    private final int maxRetries;
    private final int retryDelaySeconds;
    private final boolean failPublish;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    public OutboxPublisher(OutboxRepo repo,
                           RabbitTemplate rabbitTemplate,
                           @Value("${outbox.max-retries:5}") int maxRetries,
                           @Value("${outbox.retry-delay-seconds:10}") int retryDelaySeconds,
                           @Value("${lab.fail.publish:false}") boolean failPublish,
                           MeterRegistry meterRegistry,
                           Tracer tracer) {
        this.repo = repo;
        this.rabbitTemplate = rabbitTemplate;
        this.maxRetries = maxRetries;
        this.retryDelaySeconds = retryDelaySeconds;
        this.failPublish = failPublish;
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval-ms:1000}")
    public void pollAndPublish() {
        meterRegistry.counter("outbox.poll.total").increment();

        List<OutboxRepo.Record> batch = repo.findNew(50);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxRepo.Record r : batch) {
            Span span = tracer.nextSpan().name("outbox.publish").start();
            try {
                span.tag("event.id", r.eventId);
                span.tag("event.type", r.eventType);
                span.tag("message.id", String.valueOf(r.id));

                log.info("Outbox message publishing started",
                    kv("event", "outbox_publish_start"),
                    kv("messageId", r.id),
                    kv("eventType", r.eventType),
                    kv("eventId", r.eventId)
                );

                if (failPublish) {
                    throw new IllegalStateException("lab injected failure: outbox publish");
                }

                MessageProperties props = new MessageProperties();
                props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                props.setMessageId(r.eventId);
                props.setHeader("eventId", r.eventId);
                Message msg = new Message(r.payload.getBytes(StandardCharsets.UTF_8), props);

                CorrelationData correlationData = new CorrelationData(r.eventId);
                rabbitTemplate.send(EXCHANGE, r.eventType, msg, correlationData);
                CorrelationData.Confirm confirm = correlationData.getFuture().get(3, TimeUnit.SECONDS);
                if (confirm == null || !confirm.isAck()) {
                    throw new IllegalStateException("outbox publish was not acknowledged");
                }
                repo.markSent(r.id);

                meterRegistry.counter("outbox.publish.total", "status", "success").increment();

                span.tag("status", "success");

                log.info("Outbox message published successfully",
                    kv("event", "outbox_published"),
                    kv("messageId", r.id),
                    kv("eventType", r.eventType),
                    kv("eventId", r.eventId),
                    kv("retryCount", r.retryCount),
                    kv("status", "SUCCESS")
                );
            } catch (Exception e) {
                int nextRetryCount = r.retryCount + 1;
                repo.markFailed(r.id, nextRetryCount, maxRetries, retryDelaySeconds, trimReason(e.getMessage()));

                if (nextRetryCount >= maxRetries) {
                    meterRegistry.counter("outbox.dead.total").increment();
                } else {
                    meterRegistry.counter("outbox.retry.total").increment();
                }
                meterRegistry.counter("outbox.publish.total", "status", "failure").increment();

                span.tag("status", "failure");
                span.tag("error", e.getClass().getSimpleName());

                log.error("Outbox message publishing failed",
                    kv("event", "outbox_publish_failed"),
                    kv("messageId", r.id),
                    kv("eventType", r.eventType),
                    kv("eventId", r.eventId),
                    kv("retryCount", nextRetryCount),
                    kv("maxRetries", maxRetries),
                    kv("error", e.getMessage()),
                    kv("status", "FAILED"),
                    e
                );
            } finally {
                span.end();
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
