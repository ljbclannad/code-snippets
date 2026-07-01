package com.lx.governance.vectormemory.listener;

import com.lx.governance.vectormemory.config.RabbitMQConfig;
import com.lx.governance.vectormemory.application.governance.DeadLetterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信队列监听器
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeadLetterQueueListener {

    private final DeadLetterService deadLetterService;

    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE)
    public void handleDeadLetter(Message message) {
        String exchange = message.getMessageProperties().getReceivedExchange();
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        String body = new String(message.getBody());

        log.error("收到死信消息: exchange={}, routingKey={}, body={}", exchange, routingKey, body);

        // 提取失败原因
        String failureReason = extractFailureReason(message);

        // 保存到数据库
        try {
            deadLetterService.saveDeadLetter(message, failureReason);
        } catch (Exception e) {
            log.error("保存死信消息失败", e);
        }

        // TODO: 发送告警通知（邮件、钉钉、企业微信等）
        sendAlert(exchange, routingKey, body, failureReason);
    }

    private String extractFailureReason(Message message) {
        // 从消息头中提取失败原因
        Object reason = message.getMessageProperties().getHeaders().get("x-exception-message");
        if (reason != null) {
            return reason.toString();
        }

        Object stacktrace = message.getMessageProperties().getHeaders().get("x-exception-stacktrace");
        if (stacktrace != null) {
            return stacktrace.toString();
        }

        // 检查是否是 TTL 过期
        if (message.getMessageProperties().getHeaders().containsKey("x-first-death-reason")) {
            return "TTL expired";
        }

        return "Unknown reason";
    }

    private void sendAlert(String exchange, String routingKey, String body, String reason) {
        // TODO: 实现告警通知
        log.warn("【死信告警】exchange={}, routingKey={}, reason={}, body={}",
            exchange, routingKey, reason, body);
    }
}
