package com.lx.governance.order.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "biz.events";
    public static final String QUEUE_PAYMENT_SUCCEEDED = "q.order.payment.succeeded";
    public static final String RK_PAYMENT_SUCCEEDED = "payment.succeeded";

    @Bean
    public TopicExchange bizExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue paymentSucceededQueue() {
        return QueueBuilder.durable(QUEUE_PAYMENT_SUCCEEDED).build();
    }

    @Bean
    public Binding paymentSucceededBinding(TopicExchange bizExchange, Queue paymentSucceededQueue) {
        return BindingBuilder.bind(paymentSucceededQueue).to(bizExchange).with(RK_PAYMENT_SUCCEEDED);
    }
}
