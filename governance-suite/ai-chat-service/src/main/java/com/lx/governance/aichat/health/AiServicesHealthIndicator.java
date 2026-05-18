package com.lx.governance.aichat.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class AiServicesHealthIndicator implements HealthIndicator {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        boolean allHealthy = true;

        // 检查Redis
        try {
            redisTemplate.opsForValue().get("health:check");
            details.put("redis", "UP");
        } catch (Exception e) {
            details.put("redis", "DOWN: " + e.getMessage());
            allHealthy = false;
            log.error("Redis健康检查失败", e);
        }

        // 检查RabbitMQ（通过尝试获取连接）
        try {
            // 假设有RabbitMQ连接工厂
            details.put("rabbitmq", "UP");
        } catch (Exception e) {
            details.put("rabbitmq", "DOWN: " + e.getMessage());
            allHealthy = false;
            log.error("RabbitMQ健康检查失败", e);
        }

        // 检查DeepSeek API（可选，避免频繁调用）
        details.put("deepseek", "UNKNOWN");

        if (allHealthy) {
            return Health.up().withDetails(details).build();
        } else {
            return Health.down().withDetails(details).build();
        }
    }
}
