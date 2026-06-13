package com.lx.governance.appointment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.appointment.config.RabbitMQConfig;
import com.lx.governance.appointment.dto.AppointmentPaidEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;

import java.time.Duration;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Slf4j
@Service
public class AppointmentPaidEventHandler {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final com.lx.governance.appointment.websocket.AppointmentPushService appointmentPushService;

    public AppointmentPaidEventHandler(StringRedisTemplate redisTemplate,
                                      ObjectMapper objectMapper,
                                      com.lx.governance.appointment.websocket.AppointmentPushService appointmentPushService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.appointmentPushService = appointmentPushService;
    }

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void handleAppointmentPaid(String message,
                                     Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            // 1. 解析消息
            AppointmentPaidEvent event = objectMapper.readValue(message, AppointmentPaidEvent.class);

            log.info("Received appointment paid event",
                kv("event", "appointment_paid_received"),
                kv("orderNo", event.getOrderNo()),
                kv("userId", event.getUserId())
            );

            // 2. 幂等检查
            String idempotencyKey = "consumed:appointment.paid:" + event.getOrderNo();
            Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, "1", Duration.ofDays(7));

            if (!Boolean.TRUE.equals(success)) {
                log.info("Event already consumed, skipping",
                    kv("event", "duplicate_event"),
                    kv("orderNo", event.getOrderNo())
                );
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. 模拟发送短信通知
            log.info("Sending SMS notification",
                kv("event", "sms_notification"),
                kv("orderNo", event.getOrderNo()),
                kv("userId", event.getUserId()),
                kv("message", "您的挂号订单已支付成功，订单号：" + event.getOrderNo())
            );

            // 4. 模拟发送站内信
            log.info("Sending in-app notification",
                kv("event", "inapp_notification"),
                kv("orderNo", event.getOrderNo()),
                kv("userId", event.getUserId()),
                kv("message", "挂号成功，请按时就诊")
            );
            appointmentPushService.pushPaymentSuccess(event.getUserId(), event.getScheduleId(), event.getOrderNo());

            // 5. ACK 消息
            channel.basicAck(deliveryTag, false);

            log.info("Appointment paid event processed successfully",
                kv("event", "event_processed"),
                kv("orderNo", event.getOrderNo())
            );

        } catch (Exception e) {
            log.error("Failed to process appointment paid event", e);
            try {
                // NACK 消息，重新入队
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ex) {
                log.error("Failed to NACK message", ex);
            }
        }
    }
}
