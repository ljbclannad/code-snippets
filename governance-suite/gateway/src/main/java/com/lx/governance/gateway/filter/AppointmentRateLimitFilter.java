package com.lx.governance.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.auth.AuthHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * 挂号接口双层限流过滤器
 * <p>
 * 第一层：按 scheduleId 限流（热门科室 20/s，普通科室 10/s）
 * 第二层：按 userId 限流（2/s）
 */
@Slf4j
@Component
public class AppointmentRateLimitFilter implements GlobalFilter, Ordered {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AppointmentRateLimitFilter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        // 仅对 POST /api/appointment/grab 生效
        if (!"POST".equals(method) || !"/api/appointment/grab".equals(path)) {
            return chain.filter(exchange);
        }

        // 从请求头提取 userId（AuthTokenGlobalFilter 已注入）
        String userIdHeader = exchange.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID);
        if (userIdHeader == null) {
            log.warn("Missing X-User-Id header",
                kv("event", "rate_limit_missing_user_id"),
                kv("path", path)
            );
            return rateLimitResponse(exchange, "Missing X-User-Id header");
        }

        Long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            log.warn("Invalid X-User-Id header",
                kv("event", "rate_limit_invalid_user_id"),
                kv("userIdHeader", userIdHeader)
            );
            return rateLimitResponse(exchange, "Invalid X-User-Id header");
        }

        // 从请求体提取 scheduleId（需要缓存请求体）
        return DataBufferUtils.join(exchange.getRequest().getBody())
            .flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);

                try {
                    String body = new String(bytes, StandardCharsets.UTF_8);
                    JsonNode jsonNode = objectMapper.readTree(body);

                    if (!jsonNode.has("scheduleId")) {
                        log.warn("Missing scheduleId in request body",
                            kv("event", "rate_limit_missing_schedule_id"),
                            kv("userId", userId)
                        );
                        return rateLimitResponse(exchange, "Missing scheduleId in request body");
                    }

                    Long scheduleId = jsonNode.get("scheduleId").asLong();

                    // 查询科室热门标识（简化实现：scheduleId % 2 == 0 为热门）
                    boolean isHot = checkIfHot(scheduleId);

                    // 第一层限流：按 scheduleId
                    int scheduleReplenishRate = isHot ? 20 : 10;
                    int scheduleBurstCapacity = isHot ? 40 : 20;

                    if (!checkRateLimit("appointment:ratelimit:schedule:" + scheduleId,
                                       scheduleReplenishRate, scheduleBurstCapacity)) {
                        log.warn("Schedule rate limit exceeded",
                            kv("event", "schedule_rate_limit"),
                            kv("scheduleId", scheduleId),
                            kv("userId", userId),
                            kv("isHot", isHot),
                            kv("limit", scheduleReplenishRate)
                        );
                        return rateLimitResponse(exchange, "Schedule rate limit exceeded");
                    }

                    // 第二层限流：按 userId
                    if (!checkRateLimit("appointment:ratelimit:user:" + userId, 2, 5)) {
                        log.warn("User rate limit exceeded",
                            kv("event", "user_rate_limit"),
                            kv("userId", userId),
                            kv("scheduleId", scheduleId)
                        );
                        return rateLimitResponse(exchange, "User rate limit exceeded");
                    }

                    log.info("Rate limit passed",
                        kv("event", "rate_limit_passed"),
                        kv("userId", userId),
                        kv("scheduleId", scheduleId),
                        kv("isHot", isHot)
                    );

                    // 重新包装请求体（因为已经读取过了）
                    DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(buffer);
                        }
                    };

                    return chain.filter(exchange.mutate().request(mutatedRequest).build());

                } catch (Exception e) {
                    log.error("Failed to parse request body",
                        kv("event", "rate_limit_parse_error"),
                        kv("userId", userId),
                        e
                    );
                    return rateLimitResponse(exchange, "Invalid request body");
                }
            })
            .switchIfEmpty(rateLimitResponse(exchange, "Empty request body"));
    }

    /**
     * 检查科室是否为热门科室
     * 简化实现：scheduleId % 2 == 0 为热门
     * 实际应该从 Redis 查询：appointment:dept:hot:{deptId}
     */
    private boolean checkIfHot(Long scheduleId) {
        return scheduleId % 2 == 0;
    }

    /**
     * 检查限流
     * 使用 Redis INCR + EXPIRE 实现简化版令牌桶
     *
     * @param key Redis key
     * @param replenishRate 每秒补充速率（未使用，简化实现）
     * @param burstCapacity 突发容量（每秒最大请求数）
     * @return true 通过限流，false 被限流
     */
    private boolean checkRateLimit(String key, int replenishRate, int burstCapacity) {
        try {
            String countKey = key + ":count";
            Long count = redisTemplate.opsForValue().increment(countKey);

            if (count == null) {
                log.error("Redis increment returned null",
                    kv("event", "rate_limit_redis_error"),
                    kv("key", countKey)
                );
                return true; // 限流失败时放行
            }

            if (count == 1) {
                // 第一次请求，设置过期时间（1秒）
                redisTemplate.expire(countKey, Duration.ofSeconds(1));
            }

            return count <= burstCapacity;
        } catch (Exception e) {
            log.error("Rate limit check failed",
                kv("event", "rate_limit_check_error"),
                kv("key", key),
                e
            );
            return true; // 限流失败时放行
        }
    }

    /**
     * 返回限流响应
     */
    private Mono<Void> rateLimitResponse(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", "RATE_LIMIT_EXCEEDED");
            body.put("message", message);

            DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));

            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to serialize rate limit response", e);
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap("{\"success\":false,\"code\":\"RATE_LIMIT_EXCEEDED\",\"message\":\"Rate limit exceeded\"}"
                    .getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }
    }

    @Override
    public int getOrder() {
        return -80; // 在 AuthTokenGlobalFilter (-90) 之后执行
    }
}
