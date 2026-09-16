package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.config.RabbitConfig;
import com.lx.governance.order.domain.OrderProjectionEvent;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderProjectionEventListener {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderAdminProjectionService projectionService;

    public OrderProjectionEventListener(OrderAdminProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_ORDER_ADMIN_PROJECTION)
    public void onOrderEvent(String payload, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            OrderProjectionEvent event = objectMapper.readValue(payload, OrderProjectionEvent.class);
            Trace.set(event.traceId());
            projectionService.project(event);
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException e) {
            reject(channel, deliveryTag, e);
        } catch (Exception e) {
            requeue(channel, deliveryTag, e);
        } finally {
            Trace.clear();
        }
    }

    private void reject(Channel channel, long deliveryTag, Exception cause) {
        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception ackError) {
            throw new RuntimeException("failed to reject invalid order projection event", ackError);
        }
    }

    private void requeue(Channel channel, long deliveryTag, Exception cause) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception nackError) {
            throw new RuntimeException("failed to requeue order projection event", nackError);
        }
    }
}
