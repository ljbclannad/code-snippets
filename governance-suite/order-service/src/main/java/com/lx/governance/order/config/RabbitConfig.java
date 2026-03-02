package com.lx.governance.order.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "biz.events";
    public static final String QUEUE_PAYMENT_SUCCEEDED = "q.order.payment.succeeded";
    public static final String RK_PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String QUEUE_PAYMENT_CALLBACK = "q.order.payment.callback";
    public static final String RK_PAYMENT_CALLBACK = "payment.callback.pass";

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

    @Bean
    public Queue paymentCallbackQueue() {
        return QueueBuilder.durable(QUEUE_PAYMENT_CALLBACK).build();
    }

    @Bean
    public Binding paymentCallbackBinding(TopicExchange bizExchange, Queue paymentCallbackQueue) {
        return BindingBuilder.bind(paymentCallbackQueue).to(bizExchange).with(RK_PAYMENT_CALLBACK);
    }
}
