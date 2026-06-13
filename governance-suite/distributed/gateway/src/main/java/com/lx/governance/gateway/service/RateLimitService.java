package com.lx.governance.gateway.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.util.Collections;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 限流服务
 *
 * <p>
 * <b>作用：</b>
 * <ul>
 * <li>提供统一的限流检查能力，基于 Redis + Lua 脚本实现原子性限流</li>
 * <li>使用滑动窗口算法（固定窗口简化版），支持突发容量控制</li>
 * <li>可被多个过滤器或服务复用，避免限流逻辑重复实现</li>
 * <li>内置降级策略：Redis 故障时自动放行，保证服务可用性</li>
 * </ul>
 *
 * <p>
 * <b>核心特性：</b>
 * <ul>
 * <li><b>原子性</b>：Lua 脚本保证 GET + INCR + EXPIRE 的原子性，避免并发问题</li>
 * <li><b>高性能</b>：单次 Redis 调用完成限流检查，延迟 &lt; 1ms</li>
 * <li><b>灵活性</b>：支持自定义 key、容量、TTL，适配不同限流场景</li>
 * <li><b>可靠性</b>：异常时自动降级放行，不阻塞正常请求</li>
 * </ul>
 *
 * <p>
 * <b>使用场景：</b>
 * <ul>
 * <li>API 限流：按用户 ID、IP、接口路径限流</li>
 * <li>资源限流：按商品 ID、排班 ID 等业务维度限流</li>
 * <li>多层限流：组合使用实现双层或多层限流（如用户级 + 资源级）</li>
 * </ul>
 *
 * <p>
 * <b>示例：</b>
 * 
 * <pre>
 * // 用户级限流：每秒最多 10 次请求
 * boolean passed = rateLimitService.checkRateLimit("user:" + userId, 10);
 *
 * // 商品级限流：每秒最多 100 次请求，TTL 5 秒
 * boolean passed = rateLimitService.checkRateLimit("product:" + productId, 100, 5);
 * </pre>
 *
 * @see com.lx.governance.gateway.filter.AppointmentRateLimitFilter
 */
@Slf4j
@Service
public class RateLimitService {

    private final StringRedisTemplate redisTemplate;

    // Lua 脚本：原子性检查并递增计数器
    private static final String RATE_LIMIT_LUA_SCRIPT = "local key = KEYS[1]\n" +
            "local capacity = tonumber(ARGV[1])\n" +
            "local ttl = tonumber(ARGV[2])\n" +
            "local current = redis.call('GET', key)\n" +
            "if current == false then\n" +
            "    redis.call('SET', key, 1, 'EX', ttl)\n" +
            "    return 1\n" +
            "end\n" +
            "current = tonumber(current)\n" +
            "if current < capacity then\n" +
            "    redis.call('INCR', key)\n" +
            "    return current + 1\n" +
            "else\n" +
            "    return -1\n" +
            "end";

    private final DefaultRedisScript<Long> rateLimitScript;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>(RATE_LIMIT_LUA_SCRIPT, Long.class);
    }

    /**
     * 检查限流
     *
     * @param key           Redis key
     * @param burstCapacity 突发容量（每秒最大请求数）
     * @param ttlSeconds    TTL（秒）
     * @return true 通过限流，false 被限流
     */
    public boolean checkRateLimit(String key, int burstCapacity, int ttlSeconds) {
        try {
            String countKey = key + ":count";

            Long result = redisTemplate.execute(
                    rateLimitScript,
                    Collections.singletonList(countKey),
                    String.valueOf(burstCapacity),
                    String.valueOf(ttlSeconds));

            if (result == null) {
                log.error("Redis Lua script returned null",
                        kv("event", "rate_limit_redis_error"),
                        kv("key", countKey));
                return true; // 降级：限流失败时放行
            }

            boolean passed = result > 0; // -1 表示超限，>0 表示通过

            if (!passed) {
                log.debug("Rate limit exceeded",
                        kv("key", key),
                        kv("capacity", burstCapacity),
                        kv("current", result));
            }

            return passed;

        } catch (Exception e) {
            log.error("Rate limit check failed",
                    kv("event", "rate_limit_check_error"),
                    kv("key", key),
                    e);
            return true; // 降级：限流失败时放行
        }
    }

    /**
     * 检查限流（默认 TTL 1秒）
     */
    public boolean checkRateLimit(String key, int burstCapacity) {
        return checkRateLimit(key, burstCapacity, 1);
    }
}
