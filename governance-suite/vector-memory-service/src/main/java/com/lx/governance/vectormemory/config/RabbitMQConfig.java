package com.lx.governance.vectormemory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // 向量记忆检索
    public static final String VECTOR_MEMORY_EXCHANGE = "vector.memory.exchange";
    public static final String VECTOR_MEMORY_REQUEST_QUEUE = "vector.memory.request";

    // 记忆提取
    public static final String MEMORY_EXTRACTION_EXCHANGE = "memory.extraction.exchange";
    public static final String MEMORY_EXTRACTION_QUEUE = "memory.extraction.queue";

    // 死信队列
    public static final String DLX_EXCHANGE = "dlx.exchange";
    public static final String DLX_QUEUE = "dlx.queue";

    @Bean
    public DirectExchange vectorMemoryExchange() {
        return new DirectExchange(VECTOR_MEMORY_EXCHANGE, true, false);
    }

    @Bean
    public Queue vectorMemoryRequestQueue() {
        return QueueBuilder.durable(VECTOR_MEMORY_REQUEST_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .withArgument("x-message-ttl", 60000)
            .build();
    }

    @Bean
    public Binding vectorMemoryRequestBinding() {
        return BindingBuilder
            .bind(vectorMemoryRequestQueue())
            .to(vectorMemoryExchange())
            .with("vector.memory.request");
    }

    @Bean
    public DirectExchange memoryExtractionExchange() {
        return new DirectExchange(MEMORY_EXTRACTION_EXCHANGE, true, false);
    }

    @Bean
    public Queue memoryExtractionQueue() {
        return QueueBuilder.durable(MEMORY_EXTRACTION_QUEUE)
            .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
            .build();
    }

    @Bean
    public Binding memoryExtractionBinding() {
        return BindingBuilder
            .bind(memoryExtractionQueue())
            .to(memoryExtractionExchange())
            .with("memory.extraction");
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
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        return factory;
    }
}
