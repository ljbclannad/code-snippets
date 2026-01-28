package com.example.gateway;

import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.annotation.PostConstruct;

@Configuration
public class SentinelGatewayConfig {

//    @PostConstruct
//    public void init() {
//        GatewayCallbackManager.setBlockHandler(new JsonBlockHandler());
//    }

//    static class JsonBlockHandler implements BlockRequestHandler {
//        @Override
//        public Mono<org.springframework.web.server.ServerResponse> handleRequest(ServerWebExchange exchange,
//                Throwable t) {
//            String rule = (t instanceof BlockException) ? t.getClass().getSimpleName() : "Blocked";
//            String body = String.format("{\"success\":false,\"code\":\"SENTINEL_BLOCK\",\"message\":\"%s\"}", rule);
//            return org.springframework.web.server.ServerResponse.status(429)
//                    .contentType(MediaType.APPLICATION_JSON)
//                    .bodyValue(body);
//        }
//    }
}
