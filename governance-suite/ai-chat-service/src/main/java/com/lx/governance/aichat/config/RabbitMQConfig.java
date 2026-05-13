package com.lx.governance.aichat.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for AI Chat Service.
 * Configures exchanges for user profile, vector memory, and memory extraction.
 */
@Configuration
public class RabbitMQConfig {

    // Exchange names
    public static final String USER_PROFILE_EXCHANGE = "user.profile.exchange";
    public static final String VECTOR_MEMORY_EXCHANGE = "vector.memory.exchange";
    public static final String MEMORY_EXTRACTION_EXCHANGE = "memory.extraction.exchange";

    /**
     * User profile exchange for publishing user profile updates.
     */
    @Bean
    public DirectExchange userProfileExchange() {
        return new DirectExchange(USER_PROFILE_EXCHANGE, true, false);
    }

    /**
     * Vector memory exchange for publishing memory storage requests.
     */
    @Bean
    public DirectExchange vectorMemoryExchange() {
        return new DirectExchange(VECTOR_MEMORY_EXCHANGE, true, false);
    }

    /**
     * Memory extraction exchange for publishing memory extraction tasks.
     */
    @Bean
    public DirectExchange memoryExtractionExchange() {
        return new DirectExchange(MEMORY_EXTRACTION_EXCHANGE, true, false);
    }

    /**
     * JSON message converter for RabbitMQ messages.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
