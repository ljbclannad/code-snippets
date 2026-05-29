package com.lx.governance.appointment.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "appointment.events";
    public static final String QUEUE_NAME = "appointment.notification.queue";
    public static final String ROUTING_KEY = "appointment.paid";

    // 死信队列配置
    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String DLX_QUEUE = "dlx.queue";

    @Bean
    public TopicExchange appointmentExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue appointmentNotificationQueue() {
        // 配置死信队列和消息 TTL
        return QueueBuilder.durable(QUEUE_NAME)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-message-ttl", 60000)  // 60秒超时
            .build();
    }

    @Bean
    public Binding appointmentNotificationBinding() {
        return BindingBuilder
            .bind(appointmentNotificationQueue())
            .to(appointmentExchange())
            .with(ROUTING_KEY);
    }

    @Bean
    public FanoutExchange dlxExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue dlxQueue() {
        return QueueBuilder.durable(DLX_QUEUE).build();
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder
            .bind(dlxQueue())
            .to(dlxExchange());
    }
}
