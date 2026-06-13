package com.lx.governance.appointment.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AppointmentWebSocketConfig implements WebSocketConfigurer {

    private final AppointmentWebSocketHandler appointmentWebSocketHandler;
    private final AppointmentHandshakeInterceptor appointmentHandshakeInterceptor;

    public AppointmentWebSocketConfig(
            AppointmentWebSocketHandler appointmentWebSocketHandler,
            AppointmentHandshakeInterceptor appointmentHandshakeInterceptor) {
        this.appointmentWebSocketHandler = appointmentWebSocketHandler;
        this.appointmentHandshakeInterceptor = appointmentHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(appointmentWebSocketHandler, "/ws/appointment")
                .addInterceptors(appointmentHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }
}
