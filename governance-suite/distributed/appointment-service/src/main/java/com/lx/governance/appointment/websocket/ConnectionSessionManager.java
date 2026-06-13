package com.lx.governance.appointment.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class ConnectionSessionManager {

    private final Map<String, WebSocketSession> sessionsById = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> sessionIdsByUser = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        String sessionId = session.getId();
        sessionsById.put(sessionId, session);
        sessionIdsByUser.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(sessionId);
        log.info("WebSocket连接已注册: userId={}, sessionId={}", userId, sessionId);
    }

    public void unregister(Long userId, String sessionId) {
        sessionsById.remove(sessionId);
        if (userId != null) {
            Set<String> sessionIds = sessionIdsByUser.get(userId);
            if (sessionIds != null) {
                sessionIds.remove(sessionId);
                if (sessionIds.isEmpty()) {
                    sessionIdsByUser.remove(userId);
                }
            }
        }
        log.info("WebSocket连接已移除: userId={}, sessionId={}", userId, sessionId);
    }

    public Set<WebSocketSession> getUserSessions(Long userId) {
        Set<String> sessionIds = sessionIdsByUser.getOrDefault(userId, Set.of());
        Set<WebSocketSession> result = ConcurrentHashMap.newKeySet();
        for (String sessionId : sessionIds) {
            WebSocketSession session = sessionsById.get(sessionId);
            if (session != null && session.isOpen()) {
                result.add(session);
            }
        }
        return result;
    }

    public void closeAll() {
        for (WebSocketSession session : sessionsById.values()) {
            try {
                session.close();
            } catch (IOException e) {
                log.warn("关闭WebSocket连接失败: sessionId={}", session.getId(), e);
            }
        }
        sessionsById.clear();
        sessionIdsByUser.clear();
    }
}
