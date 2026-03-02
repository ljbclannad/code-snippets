package com.lx.governance.gateway.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.SentinelGatewayFilter;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.exception.SentinelGatewayBlockExceptionHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * Sentinel Gateway 配置
 * 
 * 1. 初始化 Sentinel Gateway 规则，按 API 路径限流
 * 2. 配置 Sentinel Gateway 异常处理
 * 3. 配置 Sentinel Gateway 过滤器
 */
@Configuration
public class SentinelGatewayConfig {

    @Value("${gateway.sentinel.order-list.qps:2}")
    private int orderListQps;

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

    /**
     * 初始化 Sentinel Gateway 规则，按 API 路径限流
     */
    @PostConstruct
    public void initGatewayRules() {
        Set<ApiDefinition> apiDefinitions = new HashSet<>();
        apiDefinitions.add(new ApiDefinition("order-list-api")
                .setPredicateItems(new HashSet<ApiPredicateItem>() {
                    {
                        add(new ApiPathPredicateItem().setPattern("/api/order/list/**")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX));
                    }
                }));
        GatewayApiDefinitionManager.loadApiDefinitions(apiDefinitions);

        Set<GatewayFlowRule> rules = new HashSet<>();
        rules.add(new GatewayFlowRule("order-list-api")
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                .setCount(orderListQps)
                .setIntervalSec(1));
        GatewayRuleManager.loadRules(rules);
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
