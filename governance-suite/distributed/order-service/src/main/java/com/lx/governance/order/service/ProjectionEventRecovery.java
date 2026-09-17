package com.lx.governance.order.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.lx.governance.order.observability.AdminOrderActivity;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 投影消费失败后先确认持久化转发，再确认原消息，避免无界立即重试。 */
@Component
public class ProjectionEventRecovery {
    private static final Logger log = LoggerFactory.getLogger(ProjectionEventRecovery.class);
    private static final String RETRY_HEADER = "projection-retry-count";

    private final RabbitTemplate rabbitTemplate;
    private final MeterRegistry meters;
    private final AdminOrderActivity activity;
    private final int maxRetries;

    public ProjectionEventRecovery(RabbitTemplate rabbitTemplate, MeterRegistry meters, AdminOrderActivity activity,
            @Value("${app.admin-observability.projection-max-retries:5}") int maxRetries) {
        this.rabbitTemplate = rabbitTemplate;
        this.meters = meters;
        this.activity = activity;
        this.maxRetries = Math.max(1, maxRetries);
    }

    public void failed(String source, String queue, String deadQueue, Message original, Channel channel,
            boolean permanent, Exception cause) {
        long deliveryTag = original.getMessageProperties().getDeliveryTag();
        int retries = retryCount(original);
        boolean dead = permanent || retries >= maxRetries;
        String destination = dead ? deadQueue : queue;
        String eventId = original.getMessageProperties().getMessageId();
        String outcome = dead ? "dead" : "retry";
        try {
            Message forwarded = MessageBuilder.fromMessage(original)
                    .setHeader(RETRY_HEADER, dead ? retries : retries + 1)
                    .build();
            CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
            rabbitTemplate.send("", destination, forwarded, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture().get(3, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new IllegalStateException("projection event forward not acknowledged");
            }
            meters.counter("admin.order.projection.events.total", "source", source, "outcome", outcome).increment();
            activity.recordBackground("PROJECTION_EVENT_FAILED", eventId, dead ? "DEAD" : "RETRY",
                    source + ":" + cause.getClass().getSimpleName());
            channel.basicAck(deliveryTag, false);
            log.warn("投影事件处理失败，已转发 source={} eventId={} outcome={} retries={} cause={}",
                    source, eventId, outcome, retries, cause.getClass().getSimpleName());
        } catch (Exception forwardingError) {
            meters.counter("admin.order.projection.events.total", "source", source, "outcome", "forward_failure")
                    .increment();
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception nackError) {
                forwardingError.addSuppressed(nackError);
            }
            throw new IllegalStateException("failed to forward projection event", forwardingError);
        }
    }

    private int retryCount(Message message) {
        Object value = message.getMessageProperties().getHeaders().get(RETRY_HEADER);
        if (value instanceof Number number) return Math.max(0, number.intValue());
        return 0;
    }
}
