package com.spring.simulation.ioc;

import java.util.HashMap;
import java.util.Map;

/**
 * 维护当前线程正在处理的请求及其 request scope Bean 缓存。
 *
 * Web 容器应在请求开始时绑定，在请求结束时清理。这里使用 ThreadLocal
 * 模拟 Servlet 模型中“一个请求由一个处理线程执行”的上下文传递方式。
 */
public final class RequestScopeContextHolder {
    private static final ThreadLocal<Map<String, Object>> REQUEST_BEANS = new ThreadLocal<>();

    private RequestScopeContextHolder() {
    }

    public static void beginRequest() {
        if (REQUEST_BEANS.get() != null) {
            throw new IllegalStateException("A request scope is already active on the current thread");
        }
        REQUEST_BEANS.set(new HashMap<>());
    }

    public static void endRequest() {
        REQUEST_BEANS.remove();
    }

    static Map<String, Object> currentRequestBeans() {
        Map<String, Object> requestBeans = REQUEST_BEANS.get();
        if (requestBeans == null) {
            throw new IllegalStateException(
                    "No active request scope. Call beginRequest() before accessing a request-scoped bean");
        }
        return requestBeans;
    }
}
