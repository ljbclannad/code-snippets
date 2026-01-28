package com.example.gateway;

import com.example.common.Trace;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class TraceIdGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(Trace.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = java.util.UUID.randomUUID().toString().replace("-", "");
        }
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(Trace.TRACE_ID, traceId)
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
