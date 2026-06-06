package com.lx.governance.appointment.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class AppointmentPushService {

    private final ConnectionSessionManager connectionSessionManager;
    private final ObjectMapper objectMapper;

    public AppointmentPushService(ConnectionSessionManager connectionSessionManager, ObjectMapper objectMapper) {
        this.connectionSessionManager = connectionSessionManager;
        this.objectMapper = objectMapper;
    }

    public void pushToUser(Long userId, String type, Object payload) {
        Set<WebSocketSession> sessions = connectionSessionManager.getUserSessions(userId);
        if (sessions.isEmpty()) {
            log.debug("用户当前无WebSocket连接: userId={}, type={}", userId, type);
            return;
        }

        AppointmentWsEvent event = AppointmentWsEvent.builder()
                .type(type)
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();
        try {
            String json = objectMapper.writeValueAsString(event);
            for (WebSocketSession session : sessions) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.error("推送预约事件失败: userId={}, type={}", userId, type, e);
        }
    }

    public void pushGrabAccepted(Long userId, Long scheduleId, String requestId, Object payload) {
        pushToUser(userId, "grab_accepted", Map.of(
                "scheduleId", scheduleId,
                "requestId", requestId,
                "data", payload
        ));
    }

    public void pushOrderCreated(Long userId, Long scheduleId, String requestId, String orderNo) {
        pushToUser(userId, "order_created", Map.of(
                "scheduleId", scheduleId,
                "requestId", requestId,
                "orderNo", orderNo
        ));
    }

    public void pushPaymentSuccess(Long userId, Long scheduleId, String orderNo) {
        pushToUser(userId, "payment_success", Map.of(
                "scheduleId", scheduleId,
                "orderNo", orderNo
        ));
    }
}
