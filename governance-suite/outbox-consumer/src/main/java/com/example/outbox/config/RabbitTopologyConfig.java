package com.example.outbox.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    @Bean
    public TopicExchange bizEventsExchange() {
        return ExchangeBuilder.topicExchange("biz.events").durable(true).build();
    }
}
