package com.lx.governance.gateway.filter;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Trace ID 全局过滤器
 * Micrometer Tracing 已自动创建 root span，这里只需要将 trace ID 添加到响应头
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Micrometer Tracing 已自动创建 root span 并传播 W3C Trace Context
        // 这里只需要将 trace ID 添加到响应头供客户端使用
        TraceContext context = tracer.currentTraceContext().context();
        if (context != null) {
            String traceId = context.traceId();
            exchange.getResponse().getHeaders().add("X-Trace-Id", traceId);
            log.debug("Gateway trace ID: {}", traceId);
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -100; // 最先执行，在 AuthTokenGlobalFilter (-90) 之前
    }
}
