package com.spring.simulation.ioc.web;

/**
 * HttpServletRequest 的最小教学模型：代表当前处理线程上的 HTTP 请求。
 */
public class HttpServletRequest {
    private final String requestUri;
    private final HttpSession session;

    public HttpServletRequest(String requestUri, HttpSession session) {
        this.requestUri = requestUri;
        this.session = session;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public HttpSession getSession() {
        return session;
    }
}
