package com.spring.simulation.ioc.scope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 维护 session scope Bean 缓存及当前处理线程绑定的会话标识。
 *
 * 同一 session 可以被不同请求线程访问，因此 Bean 缓存按 sessionId 全局保存；
 * 当前线程只保存本次请求关联的 sessionId。请求结束时只解绑线程，
 * 会话失效时才清理对应 Bean。
 */
public final class SessionScopeContextHolder {
    private static final Map<String, Map<String, Object>> SESSION_BEANS = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> CURRENT_SESSION_ID = new ThreadLocal<>();

    private SessionScopeContextHolder() {
    }

    public static void beginSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("Session id must not be empty");
        }
        CURRENT_SESSION_ID.set(sessionId);
    }

    public static void endSession() {
        CURRENT_SESSION_ID.remove();
    }

    public static void invalidateSession(String sessionId) {
        SESSION_BEANS.remove(sessionId);
        if (sessionId.equals(CURRENT_SESSION_ID.get())) {
            CURRENT_SESSION_ID.remove();
        }
    }

    static Map<String, Object> currentSessionBeans() {
        String sessionId = CURRENT_SESSION_ID.get();
        if (sessionId == null) {
            throw new IllegalStateException(
                    "No active session scope. Call beginSession(sessionId) before accessing a session-scoped bean");
        }
        return SESSION_BEANS.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>());
    }
}
