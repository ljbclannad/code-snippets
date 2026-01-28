package com.example.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

@Configuration
public class KeyResolverConfig {

    /**
     * 简单演示：按客户端 IP 限流。
     * 生产上建议：用户维度（userId）、租户维度（tenantId/hospitalId）+ IP 兜底。
     */
    @Bean
    public KeyResolver ipKeyResolver() {
        return (ServerWebExchange exchange) -> {
            String ip = exchange.getRequest().getHeaders().getFirst("X-Real-IP");
            if (ip == null || ip.isBlank()) {
                ip = exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "unknown";
            }
            return Mono.just(ip);
        };
    }
}
