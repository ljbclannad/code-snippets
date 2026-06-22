package com.lx.governance.streamdemo.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class MarkdownWebSocketConfig implements WebSocketConfigurer {

    private final MarkdownWebSocketHandler markdownWebSocketHandler;

    public MarkdownWebSocketConfig(MarkdownWebSocketHandler markdownWebSocketHandler) {
        this.markdownWebSocketHandler = markdownWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(markdownWebSocketHandler, "/ws/markdown")
            .setAllowedOrigins("*");
    }
}
