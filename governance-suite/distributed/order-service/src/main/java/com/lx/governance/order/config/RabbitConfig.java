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
    public static final String QUEUE_ORDER_ADMIN_PROJECTION = "q.order.admin.projection";
    public static final String RK_ORDER_PROJECTION = "order.#";
    public static final String QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION = "q.order.admin.payment.projection";
    public static final String RK_PAYMENT_STATUS_CHANGED = "payment.status.changed";

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
        return BindingBuilder.bind(paymentSucceededQueue).to(bizExchange).with(RK_PAYMENT_STATUS_CHANGED);
    }

    @Bean
    public Queue paymentCallbackQueue() {
        return QueueBuilder.durable(QUEUE_PAYMENT_CALLBACK).build();
    }

    @Bean
    public Binding paymentCallbackBinding(TopicExchange bizExchange, Queue paymentCallbackQueue) {
        return BindingBuilder.bind(paymentCallbackQueue).to(bizExchange).with(RK_PAYMENT_CALLBACK);
    }

    @Bean
    public Queue orderAdminProjectionQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_ADMIN_PROJECTION).build();
    }

    @Bean
    public Binding orderAdminProjectionBinding(TopicExchange bizExchange, Queue orderAdminProjectionQueue) {
        return BindingBuilder.bind(orderAdminProjectionQueue).to(bizExchange).with(RK_ORDER_PROJECTION);
    }

    @Bean
    public Queue orderAdminPaymentProjectionQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_ADMIN_PAYMENT_PROJECTION).build();
    }

    @Bean
    public Binding orderAdminPaymentProjectionBinding(TopicExchange bizExchange, Queue orderAdminPaymentProjectionQueue) {
        return BindingBuilder.bind(orderAdminPaymentProjectionQueue).to(bizExchange).with(RK_PAYMENT_STATUS_CHANGED);
    }

}
