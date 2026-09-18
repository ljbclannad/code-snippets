package com.lx.governance.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ProductEventListener {
    private final ObjectMapper mapper;
    private final ElasticsearchProductIndex index;
    private final MeterRegistry meters;

    public ProductEventListener(ObjectMapper mapper, ElasticsearchProductIndex index, MeterRegistry meters) {
        this.mapper = mapper;
        this.index = index;
        this.meters = meters;
    }

    @RabbitListener(queues = "q.product.search")
    public void consume(Message message) {
        final ProductChanged event;
        try {
            if (message.getBody().length > 32768) throw new IllegalArgumentException("商品事件过大");
            event = mapper.readValue(message.getBody(), ProductChanged.class);
            if (event == null || event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 64
                    || !"product.changed".equals(event.eventType()) || event.product() == null) {
                throw new IllegalArgumentException("商品事件格式无效");
            }
        } catch (Exception exception) {
            meters.counter("product.search.projection.total", "result", "invalid").increment();
            throw new AmqpRejectAndDontRequeueException("商品事件无效", exception);
        }
        try {
            boolean applied = index.project(event.product());
            meters.counter("product.search.projection.total", "result", applied ? "applied" : "stale").increment();
        } catch (SearchUnavailableException exception) {
            meters.counter("product.search.projection.total", "result", "failed").increment();
            throw exception;
        }
    }

    public record ProductChanged(String eventId, String eventType, ProductDocument product) { }
}
