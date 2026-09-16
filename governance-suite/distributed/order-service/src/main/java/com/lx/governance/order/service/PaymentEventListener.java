package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.config.RabbitConfig;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    private final ObjectMapper objectMapper;
    private final OrderAppService appService;

    public PaymentEventListener(OrderAppService appService, ObjectMapper objectMapper) {
        this.appService = appService;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_PAYMENT_SUCCEEDED)
    public void onPaymentSucceeded(String message, Message amqpMessage, Channel channel) {
        long tag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            JsonNode node = objectMapper.readTree(message);
            String eventId = node.path("eventId").asText(null);
            String orderNo = node.path("orderNo").asText(null);
            String paymentStatus = node.path("paymentStatus").asText(null);
            String traceId = node.has("traceId") ? node.get("traceId").asText() : null;
            Trace.set(traceId);
            if (eventId == null || eventId.isBlank() || orderNo == null || orderNo.isBlank()) {
                throw new IllegalArgumentException("missing payment status event identifiers");
            }
            if (!"SUCCEEDED".equals(paymentStatus)) {
                ack(channel, tag);
                return;
            }
            if (!appService.markPaidFromEvent(eventId, "payment.succeeded", orderNo)) {
                log.info("Skip duplicate payment.succeeded eventId={} orderNo={}", eventId, orderNo);
                ack(channel, tag);
                return;
            }
            log.info("Received payment.succeeded for orderNo={} message={}", orderNo, message);
            ack(channel, tag);
        } catch (IllegalArgumentException e) {
            log.error("Reject invalid payment.succeeded message={}", message, e);
            nackDiscard(channel, tag);
        } catch (Exception e) {
            log.error("Failed to handle payment.succeeded message={}", message, e);
            nackRequeue(channel, tag);
        } finally {
            Trace.clear();
        }
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_PAYMENT_CALLBACK)
    public void onPaymentCallback(String message, Message amqpMessage, Channel channel) {
        long tag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            JsonNode node = objectMapper.readTree(message);
            String eventId = node.path("eventId").asText(null);
            String orderNo = node.get("orderNo").asText();
            String traceId = node.has("traceId") ? node.get("traceId").asText() : null;
            Trace.set(traceId);
            if (eventId == null || eventId.isBlank()) {
                throw new IllegalArgumentException("missing eventId");
            }
            if (!appService.markPaidFromEvent(eventId, "payment.callback.pass", orderNo)) {
                log.info("Skip duplicate payment.callback.pass eventId={} orderNo={}", eventId, orderNo);
                ack(channel, tag);
                return;
            }
            log.info("Received payment.callback.pass for orderNo={} message={}", orderNo, message);
            ack(channel, tag);
        } catch (Exception e) {
            log.error("Failed to handle payment.callback.pass message={}", message, e);
            nackRequeue(channel, tag);
        } finally {
            Trace.clear();
        }
    }

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void nackRequeue(Channel channel, long tag) {
        try {
            channel.basicNack(tag, false, true);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private void nackDiscard(Channel channel, long tag) {
        try {
            channel.basicNack(tag, false, false);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
