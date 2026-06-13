package com.lx.governance.order.context;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 用户读写上下文
 * 用于标记用户的写操作，在短时间内强制读主库，避免主从延迟导致的数据不一致
 */
@Component
public class UserReadWriteContext {

    private static final String REDIS_KEY_PREFIX = "user:write:";
    private static final long FORCE_MASTER_DURATION_MS = 3000; // 3秒内读主库

    private final StringRedisTemplate redisTemplate;

    public UserReadWriteContext(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 标记用户发生了写操作
     * @param userId 用户ID
     */
    public void markUserWrite(Long userId) {
        if (userId == null) {
            return;
        }
        String key = REDIS_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()),
            FORCE_MASTER_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 检查用户是否应该读主库
     * @param userId 用户ID
     * @return true-读主库，false-读从库
     */
    public boolean shouldReadMaster(Long userId) {
        if (userId == null) {
            return false;
        }
        String key = REDIS_KEY_PREFIX + userId;
        String writeTime = redisTemplate.opsForValue().get(key);
        return writeTime != null; // Redis TTL 自动过期
    }
}
