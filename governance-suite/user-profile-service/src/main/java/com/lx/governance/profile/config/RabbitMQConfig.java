package com.lx.governance.profile.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String USER_PROFILE_EXCHANGE = "user.profile.exchange";
    public static final String USER_PROFILE_REQUEST_QUEUE = "user.profile.request";
    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String DLX_QUEUE = "dlx.queue";

    @Bean
    public DirectExchange userProfileExchange() {
        return new DirectExchange(USER_PROFILE_EXCHANGE, true, false);
    }

    @Bean
    public Queue userProfileRequestQueue() {
        return QueueBuilder.durable(USER_PROFILE_REQUEST_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-message-ttl", 60000)
            .build();
    }

    @Bean
    public Binding userProfileRequestBinding() {
        return BindingBuilder
            .bind(userProfileRequestQueue())
            .to(userProfileExchange())
            .with("user.profile.request");
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

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
