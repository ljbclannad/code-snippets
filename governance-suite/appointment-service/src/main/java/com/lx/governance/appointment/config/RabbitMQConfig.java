package com.lx.governance.appointment.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "appointment.events";
    public static final String QUEUE_NAME = "appointment.notification.queue";
    public static final String ROUTING_KEY = "appointment.paid";

    @Bean
    public TopicExchange appointmentExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public Queue appointmentNotificationQueue() {
        return new Queue(QUEUE_NAME, true);
    }

    @Bean
    public Binding appointmentNotificationBinding() {
        return BindingBuilder
            .bind(appointmentNotificationQueue())
            .to(appointmentExchange())
            .with(ROUTING_KEY);
    }
}
