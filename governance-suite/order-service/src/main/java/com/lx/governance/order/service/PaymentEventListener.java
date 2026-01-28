package com.lx.governance.order.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OrderAppService appService;

    public PaymentEventListener(OrderAppService appService) {
        this.appService = appService;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_PAYMENT_SUCCEEDED)
    public void onPaymentSucceeded(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            String orderNo = node.get("orderNo").asText();
            String traceId = node.has("traceId") ? node.get("traceId").asText() : null;
            Trace.set(traceId);
            log.info("Received payment.succeeded for orderNo={} message={}", orderNo, message);
            appService.markPaid(orderNo);
        } catch (Exception e) {
            log.error("Failed to handle payment.succeeded message={}", message, e);
            throw new RuntimeException(e);
        } finally {
            Trace.clear();
        }
    }
}
