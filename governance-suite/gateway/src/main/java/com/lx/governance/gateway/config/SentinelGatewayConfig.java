package com.lx.governance.gateway.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;

import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;

@Configuration
public class SentinelGatewayConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SentinelGatewayBlockExceptionHandler sentinelGatewayBlockExceptionHandler(
            ObjectProvider<List<ViewResolver>> viewResolversProvider,
            ObjectProvider<org.springframework.http.codec.ServerCodecConfigurer> codecConfigurerProvider) {
        List<ViewResolver> viewResolvers = viewResolversProvider.getIfAvailable(Collections::emptyList);
        org.springframework.http.codec.ServerCodecConfigurer codecConfigurer = codecConfigurerProvider.getIfAvailable();
        SentinelGatewayBlockExceptionHandler handler = new SentinelGatewayBlockExceptionHandler(viewResolvers,
                codecConfigurer);
        GatewayCallbackManager.setBlockHandler(jsonBlockRequestHandler());
        return handler;
    }

    @Bean
    @Order(-1)
    public SentinelGatewayFilter sentinelGatewayFilter() {
        return new SentinelGatewayFilter();
    }

    @Bean
    public BlockRequestHandler jsonBlockRequestHandler() {
        return (ServerWebExchange exchange, Throwable t) -> {
            Map<String, Object> body = new HashMap<>();
            body.put("success", false);
            body.put("code", "GATEWAY_BLOCKED");
            body.put("message", "Blocked by Sentinel: "
                    + (t instanceof BlockException ? t.getClass().getSimpleName() : String.valueOf(t)));
            String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
            body.put("traceId", traceId);

            return ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .bodyValue(body);
        };
    }
}
