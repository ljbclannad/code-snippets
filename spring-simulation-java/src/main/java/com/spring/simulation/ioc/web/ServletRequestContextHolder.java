package com.spring.simulation.ioc.web;

/**
 * 保存当前处理线程的 HTTP 请求，模拟 Servlet 容器的请求线程绑定。
 */
public final class ServletRequestContextHolder {
    private static final ThreadLocal<HttpServletRequest> CURRENT_REQUEST = new ThreadLocal<>();

    private ServletRequestContextHolder() {
    }

    public static void bind(HttpServletRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("HttpServletRequest must not be null");
        }
        if (CURRENT_REQUEST.get() != null) {
            throw new IllegalStateException("A servlet request is already active on the current thread");
        }
        CURRENT_REQUEST.set(request);
    }

    public static HttpServletRequest currentRequest() {
        HttpServletRequest request = CURRENT_REQUEST.get();
        if (request == null) {
            throw new IllegalStateException(
                    "No active servlet request. Call beginRequest(HttpServletRequest) before resolving it");
        }
        return request;
    }

    public static HttpSession currentSession() {
        HttpSession session = currentRequest().getSession();
        if (session == null) {
            throw new IllegalStateException("The current servlet request has no HTTP session");
        }
        return session;
    }

    public static void clear() {
        CURRENT_REQUEST.remove();
    }
}
