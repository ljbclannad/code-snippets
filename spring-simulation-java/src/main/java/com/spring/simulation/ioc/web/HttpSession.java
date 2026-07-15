package com.spring.simulation.ioc.web;

/**
 * HttpSession 的最小教学模型。
 */
public class HttpSession {
    private final String id;

    public HttpSession(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Session id must not be empty");
        }
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
