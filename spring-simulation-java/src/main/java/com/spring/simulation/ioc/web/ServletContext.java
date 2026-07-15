package com.spring.simulation.ioc.web;

/**
 * ServletContext 的最小教学模型：代表整个 Web 应用的共享上下文。
 */
public class ServletContext {
    private final String contextPath;

    public ServletContext(String contextPath) {
        this.contextPath = contextPath;
    }

    public String getContextPath() {
        return contextPath;
    }
}
