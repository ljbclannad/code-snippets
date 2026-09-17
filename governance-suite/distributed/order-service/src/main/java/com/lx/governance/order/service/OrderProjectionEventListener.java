package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.config.RabbitConfig;
import com.lx.governance.order.domain.OrderProjectionEvent;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class OrderProjectionEventListener {
    private final ObjectMapper objectMapper;
    private final OrderAdminProjectionService projectionService;
    private final ProjectionEventRecovery recovery;
    private final MeterRegistry meters;

    public OrderProjectionEventListener(OrderAdminProjectionService projectionService, ObjectMapper objectMapper,
            ProjectionEventRecovery recovery, MeterRegistry meters) {
        this.projectionService = projectionService;
        this.objectMapper = objectMapper;
        this.recovery = recovery;
        this.meters = meters;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_ORDER_ADMIN_PROJECTION)
    public void onOrderEvent(String payload, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            OrderProjectionEvent event = objectMapper.readValue(payload, OrderProjectionEvent.class);
            Trace.set(event.traceId());
            projectionService.project(event);
            meters.counter("admin.order.projection.events.total", "source", "order", "outcome", "success").increment();
            channel.basicAck(deliveryTag, false);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            recovery.failed("order", RabbitConfig.QUEUE_ORDER_ADMIN_PROJECTION,
                    RabbitConfig.QUEUE_ORDER_ADMIN_PROJECTION_DEAD, message, channel, true, e);
        } catch (Exception e) {
            recovery.failed("order", RabbitConfig.QUEUE_ORDER_ADMIN_PROJECTION,
                    RabbitConfig.QUEUE_ORDER_ADMIN_PROJECTION_DEAD, message, channel, false, e);
        } finally {
            Trace.clear();
        }
    }

}
