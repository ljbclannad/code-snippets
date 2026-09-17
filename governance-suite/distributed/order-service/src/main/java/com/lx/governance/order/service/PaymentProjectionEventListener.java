package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.time.Instant;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.config.RabbitConfig;
import com.lx.governance.order.domain.PaymentProjectionEvent;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentProjectionEventListener {
    private final PaymentAdminProjectionService projectionService;
    private final ObjectMapper objectMapper;
    private final ProjectionEventRecovery recovery;
    private final MeterRegistry meters;

    public PaymentProjectionEventListener(PaymentAdminProjectionService projectionService, ObjectMapper objectMapper,
            ProjectionEventRecovery recovery, MeterRegistry meters) {
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
        this.recovery = recovery;
        this.meters = meters;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION)
    public void onPaymentEvent(String payload, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            PaymentProjectionEvent event = objectMapper.readValue(payload, PaymentProjectionEvent.class);
            Trace.set(event.traceId());
            projectionService.project(event);
            if (event.occurredAt() != null) {
                long lag = Math.max(0, Duration.between(event.occurredAt(), Instant.now()).toMillis());
                meters.summary("admin.order.payment.projection.lag.ms").record(lag);
            }
            meters.counter("admin.order.projection.events.total", "source", "payment", "outcome", "success").increment();
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            recovery.failed("payment", RabbitConfig.QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION,
                    RabbitConfig.QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION_DEAD, message, channel, true, exception);
        } catch (Exception exception) {
            recovery.failed("payment", RabbitConfig.QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION,
                    RabbitConfig.QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION_DEAD, message, channel, false, exception);
        } finally {
            Trace.clear();
        }
    }

}
