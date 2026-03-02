package com.lx.governance.gateway.config;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = firstIp(exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"));
            if (ip == null || ip.isBlank()) {
                ip = firstIp(exchange.getRequest().getHeaders().getFirst("X-Real-IP"));
            }
            if (ip == null || ip.isBlank()) {
                InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
                ip = (remoteAddress != null && remoteAddress.getAddress() != null)
                        ? remoteAddress.getAddress().getHostAddress()
                        : "unknown";
            }
            return Mono.just(ip);
        };
    }

    private String firstIp(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        return headerValue.split(",")[0].trim();
    }
}
