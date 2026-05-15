package com.lx.governance.aichat.config;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

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

    // 对话历史同步队列
    public static final String CHAT_HISTORY_SYNC_QUEUE = "chat.history.sync.queue";
    public static final String CHAT_HISTORY_SYNC_EXCHANGE = "chat.history.sync.exchange";
    public static final String CHAT_HISTORY_SYNC_ROUTING_KEY = "chat.history.sync";

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

    /**
     * RabbitTemplate with reply support for RPC
     */
    @Bean
    public org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        org.springframework.amqp.rabbit.core.RabbitTemplate template =
            new org.springframework.amqp.rabbit.core.RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setReplyTimeout(10000);
        template.setMandatory(true);
        return template;
    }

    /**
     * Configure RabbitListener container factory with JSON message converter
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        return factory;
    }

    /**
     * 固定的用户画像响应队列
     */
    @Bean
    public Queue userProfileReplyQueue() {
        return new Queue("ai.chat.user.profile.reply", true);
    }

    /**
     * 固定的向量记忆响应队列
     */
    @Bean
    public Queue vectorMemoryReplyQueue() {
        return new Queue("ai.chat.vector.memory.reply", true);
    }

    /**
     * 对话历史同步队列
     */
    @Bean
    public Queue chatHistorySyncQueue() {
        return new Queue(CHAT_HISTORY_SYNC_QUEUE, true);
    }

    /**
     * 对话历史同步交换机
     */
    @Bean
    public DirectExchange chatHistorySyncExchange() {
        return new DirectExchange(CHAT_HISTORY_SYNC_EXCHANGE, true, false);
    }

    /**
     * 对话历史同步绑定
     */
    @Bean
    public org.springframework.amqp.core.Binding chatHistorySyncBinding() {
        return org.springframework.amqp.core.BindingBuilder
                .bind(chatHistorySyncQueue())
                .to(chatHistorySyncExchange())
                .with(CHAT_HISTORY_SYNC_ROUTING_KEY);
    }
}
