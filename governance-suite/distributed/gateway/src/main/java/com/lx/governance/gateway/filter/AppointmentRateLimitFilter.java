package com.lx.governance.gateway.filter;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.gateway.config.AppointmentRateLimitProperties;
import com.lx.governance.gateway.service.HotDeptCacheService;
import com.lx.governance.gateway.service.RateLimitService;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 挂号接口双层限流过滤器（优化版）
 * <p>
 * 优化点：
 * 1. 配置化：限流参数可通过配置文件调整
 * 2. 本地缓存：热门科室信息使用 Caffeine 缓存，减少 Redis 查询
 * 3. 职责分离：限流逻辑、缓存逻辑分离到独立服务
 * 4. 可观测性：增强日志和监控指标
 */
@Slf4j
@Component
public class AppointmentRateLimitFilter implements GlobalFilter, Ordered {

    private final AppointmentRateLimitProperties properties;
    private final HotDeptCacheService hotDeptCacheService;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public AppointmentRateLimitFilter(
            AppointmentRateLimitProperties properties,
            HotDeptCacheService hotDeptCacheService,
            RateLimitService rateLimitService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.hotDeptCacheService = hotDeptCacheService;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 检查是否启用限流
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        // 仅对配置的目标路径生效
        if (!"POST".equals(method) || !properties.getTargetPath().equals(path)) {
            return chain.filter(exchange);
        }

        // 提取 userId
        Long userId = extractUserId(exchange);
        if (userId == null) {
            return rateLimitResponse(exchange, "Missing or invalid X-User-Id header");
        }

        // 解析请求体获取 scheduleId
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> processRequest(exchange, chain, dataBuffer, userId))
                .switchIfEmpty(rateLimitResponse(exchange, "Empty request body"));
    }

    /**
     * 处理请求：解析请求体、执行双层限流、放行
     */
    private Mono<Void> processRequest(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            DataBuffer dataBuffer,
            Long userId) {

        byte[] bytes = new byte[dataBuffer.readableByteCount()];
        dataBuffer.read(bytes);
        DataBufferUtils.release(dataBuffer);

        try {
            String body = new String(bytes, StandardCharsets.UTF_8);
            JsonNode jsonNode = objectMapper.readTree(body);

            if (!jsonNode.has("scheduleId")) {
                log.warn("Missing scheduleId in request body",
                        kv("event", "rate_limit_missing_schedule_id"),
                        kv("userId", userId));
                return rateLimitResponse(exchange, "Missing scheduleId in request body");
            }

            Long scheduleId = jsonNode.get("scheduleId").asLong();

            // 执行双层限流
            return executeRateLimit(exchange, chain, bytes, userId, scheduleId);

        } catch (Exception e) {
            log.error("Failed to parse request body",
                    kv("event", "rate_limit_parse_error"),
                    kv("userId", userId),
                    e);
            return rateLimitResponse(exchange, "Invalid request body");
        }
    }

    /**
     * 执行双层限流
     */
    private Mono<Void> executeRateLimit(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            byte[] requestBody,
            Long userId,
            Long scheduleId) {

        // 查询科室是否为热门（带本地缓存）
        boolean isHot = hotDeptCacheService.isHotDept(scheduleId);

        // 第一层限流：按 scheduleId
        AppointmentRateLimitProperties.RateLimitConfig scheduleConfig = isHot ? properties.getHotDept()
                : properties.getNormalDept();

        if (!rateLimitService.checkRateLimit(
                "appointment:ratelimit:schedule:" + scheduleId,
                scheduleConfig.getBurstCapacity())) {

            log.warn("Schedule rate limit exceeded",
                    kv("event", "schedule_rate_limit"),
                    kv("scheduleId", scheduleId),
                    kv("userId", userId),
                    kv("isHot", isHot),
                    kv("limit", scheduleConfig.getReplenishRate()));
            return rateLimitResponse(exchange, "Schedule rate limit exceeded");
        }

        // 第二层限流：按 userId
        if (!rateLimitService.checkRateLimit(
                "appointment:ratelimit:user:" + userId,
                properties.getUser().getBurstCapacity())) {

            log.warn("User rate limit exceeded",
                    kv("event", "user_rate_limit"),
                    kv("userId", userId),
                    kv("scheduleId", scheduleId));
            return rateLimitResponse(exchange, "User rate limit exceeded");
        }

        log.info("Rate limit passed",
                kv("event", "rate_limit_passed"),
                kv("userId", userId),
                kv("scheduleId", scheduleId),
                kv("isHot", isHot));

        // 重新包装请求体
        return forwardRequest(exchange, chain, requestBody);
    }

    /**
     * 从请求头提取 userId
     */
    private Long extractUserId(ServerWebExchange exchange) {
        String userIdHeader = exchange.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID);
        if (userIdHeader == null) {
            log.warn("Missing X-User-Id header",
                    kv("event", "rate_limit_missing_user_id"),
                    kv("path", exchange.getRequest().getPath().value()));
            return null;
        }

        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException e) {
            log.warn("Invalid X-User-Id header",
                    kv("event", "rate_limit_invalid_user_id"),
                    kv("userIdHeader", userIdHeader));
            return null;
        }
    }

    /**
     * 重新包装请求体并转发请求
     */
    private Mono<Void> forwardRequest(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            byte[] requestBody) {

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(requestBody);
        ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public Flux<DataBuffer> getBody() {
                return Flux.just(buffer);
            }
        };

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
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
