package com.lx.governance.appointment.websocket;

import java.util.Map;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AppointmentWebSocketHandler extends TextWebSocketHandler {

    private final ConnectionSessionManager connectionSessionManager;
    private final ObjectMapper objectMapper;

    public AppointmentWebSocketHandler(
            ConnectionSessionManager connectionSessionManager,
            ObjectMapper objectMapper) {
        this.connectionSessionManager = connectionSessionManager;
        this.objectMapper = objectMapper;
    }

    @SuppressWarnings("null")
    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        connectionSessionManager.register(userId, session);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                AppointmentWsEvent.builder()
                        .type("connected")
                        .timestamp(System.currentTimeMillis())
                        .payload(Map.of("userId", userId))
                        .build())));
    }

    @SuppressWarnings("null")
    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        JsonNode payload = objectMapper.readTree(message.getPayload());
        String type = payload.path("type").asText("");
        if ("ping".equals(type)) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(
                    AppointmentWsEvent.builder()
                            .type("pong")
                            .timestamp(System.currentTimeMillis())
                            .payload(Map.of())
                            .build())));
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        connectionSessionManager.unregister(userId, session.getId());
    }

    @SuppressWarnings("null")
    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) throws Exception {
        Long userId = (Long) session.getAttributes().get("userId");
        connectionSessionManager.unregister(userId, session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }
}
