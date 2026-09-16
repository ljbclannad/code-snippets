package com.lx.governance.payment.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String EXCHANGE = "biz.events";
    public static final String RK_PAYMENT_STATUS_CHANGED = "payment.status.changed";

    @Bean
    public TopicExchange bizExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
