package com.lx.governance.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.auth.AuthTokenClaims;
import com.lx.governance.common.auth.AuthTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class AuthTokenGlobalFilter implements GlobalFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(AuthTokenGlobalFilter.class);
    private final AuthTokenService authTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AuthTokenGlobalFilter(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (skip(path)) {
            return chain.filter(exchange);
        }

        try {
            AuthTokenClaims claims = authTokenService.verify(exchange.getRequest().getHeaders().getFirst(AuthHeaders.AUTHORIZATION));
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove(AuthHeaders.USER_ID);
                        headers.remove(AuthHeaders.USERNAME);
                        headers.set(AuthHeaders.USER_ID, String.valueOf(claims.getUserId()));
                        headers.set(AuthHeaders.USERNAME, claims.getUsername());
                    })
                    .build();

            log.info("Request authenticated",
                kv("event", "auth_success"),
                kv("userId", claims.getUserId()),
                kv("path", path),
                kv("method", exchange.getRequest().getMethod())
            );

            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            log.warn("Request authentication failed",
                kv("event", "auth_failed"),
                kv("path", path),
                kv("method", exchange.getRequest().getMethod()),
                kv("reason", "invalid_token")
            );
            return unauthorized(exchange, e.getMessage());
        }
    }

    private boolean skip(String path) {
        return path.startsWith("/api/user/register")
                || path.startsWith("/api/user/login")
                || path.startsWith("/api/user/auth/validate")
                || path.startsWith("/actuator");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", "UNAUTHORIZED");
            body.put("message", message == null ? "invalid token" : message);
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                    .wrap(objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            DataBuffer buffer = exchange.getResponse().bufferFactory()
                    .wrap("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"invalid token\"}".getBytes(StandardCharsets.UTF_8));
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
