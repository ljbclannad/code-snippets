package com.health.agent.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 缓存配置
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /**
     * 缓存管理器
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 默认缓存配置：30分钟过期
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
            )
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(createJsonSerializer())
            )
            .disableCachingNullValues();

        // 针对不同缓存的个性化配置
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // 患者档案缓存：1小时过期
        cacheConfigurations.put("patientProfile", defaultConfig.entryTtl(Duration.ofHours(1)));

        // 体重记录缓存：30分钟过期
        cacheConfigurations.put("weightRecords", defaultConfig.entryTtl(Duration.ofMinutes(30)));

        // 聊天记录缓存：15分钟过期
        cacheConfigurations.put("chatHistory", defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // 处方记录缓存：1小时过期
        cacheConfigurations.put("prescriptionHistory", defaultConfig.entryTtl(Duration.ofHours(1)));

        // 向量检索缓存：10分钟过期（相似病例检索结果）
        cacheConfigurations.put("vectorSearch", defaultConfig.entryTtl(Duration.ofMinutes(10)));

        // Agent 会话缓存：5分钟过期
        cacheConfigurations.put("agentSession", defaultConfig.entryTtl(Duration.ofMinutes(5)));

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultConfig)
            .withInitialCacheConfigurations(cacheConfigurations)
            .transactionAware()
            .build();
    }

    /**
     * 创建 JSON 序列化器
     */
    private GenericJackson2JsonRedisSerializer createJsonSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();

        // 注册 JavaTimeModule 以支持 LocalDateTime 等类型
        objectMapper.registerModule(new JavaTimeModule());

        // 启用类型信息，以便反序列化时能正确识别类型
        objectMapper.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );

        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }
}
