package com.lx.governance.payment.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String EXCHANGE = "biz.events";
    public static final String RK_PAYMENT_SUCCEEDED = "payment.succeeded";

    @Bean
    public TopicExchange bizExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }
}
