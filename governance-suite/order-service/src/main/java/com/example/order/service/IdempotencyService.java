package com.example.order.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class IdempotencyService {
    private final StringRedisTemplate redis;

    public IdempotencyService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 简化示例：用 SET NX 做幂等锁。
     * - 成功返回 true：本次可以执行
     * - 失败返回 false：说明重复请求
     */
    public boolean tryAcquire(String bizKey, Duration ttl) {
        Boolean ok = redis.opsForValue().setIfAbsent("idem:" + bizKey, "1", ttl);
        return Boolean.TRUE.equals(ok);
    }
}
