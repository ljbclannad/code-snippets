package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.config.RabbitConfig;
import com.lx.governance.order.domain.PaymentProjectionEvent;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentProjectionEventListener {
    private final PaymentAdminProjectionService projectionService;
    private final ObjectMapper objectMapper;

    public PaymentProjectionEventListener(PaymentAdminProjectionService projectionService, ObjectMapper objectMapper) {
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION)
    public void onPaymentEvent(String payload, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            PaymentProjectionEvent event = objectMapper.readValue(payload, PaymentProjectionEvent.class);
            Trace.set(event.traceId());
            projectionService.project(event);
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException exception) {
            nack(channel, deliveryTag, false, exception);
        } catch (Exception exception) {
            nack(channel, deliveryTag, true, exception);
        } finally {
            Trace.clear();
        }
    }

    private void nack(Channel channel, long deliveryTag, boolean requeue, Exception cause) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (Exception nackError) {
            throw new IllegalStateException("failed to nack payment projection event", nackError);
        }
    }
}
