package com.spring.simulation.ioc.web;

/**
 * ServletConfig 的最小教学模型：保存 Servlet 自身配置及所属上下文。
 */
public class ServletConfig {
    private final String servletName;
    private final ServletContext servletContext;

    public ServletConfig(String servletName, ServletContext servletContext) {
        this.servletName = servletName;
        this.servletContext = servletContext;
    }

    public String getServletName() {
        return servletName;
    }

    public ServletContext getServletContext() {
        return servletContext;
    }
}
