package com.example.payment.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    @Bean
    public TopicExchange bizEventsExchange() {
        return ExchangeBuilder.topicExchange("biz.events").durable(true).build();
    }

    @Bean
    public Queue paymentSucceededQueue() {
        return QueueBuilder.durable("q.payment.succeeded").build();
    }

    @Bean
    public Binding bindPaymentSucceeded(Queue paymentSucceededQueue, TopicExchange bizEventsExchange) {
        return BindingBuilder.bind(paymentSucceededQueue).to(bizEventsExchange).with("payment.succeeded");
    }
}
