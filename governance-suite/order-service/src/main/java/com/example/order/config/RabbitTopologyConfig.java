package com.example.order.config;

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

    @Bean
    public Queue orderCreatedQueue() {
        return QueueBuilder.durable("q.order.created").build();
    }

    @Bean
    public Binding bindOrderCreated(Queue orderCreatedQueue, TopicExchange bizEventsExchange) {
        return BindingBuilder.bind(orderCreatedQueue).to(bizEventsExchange).with("order.created");
    }
}
