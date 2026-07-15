package com.spring.simulation.ioc.lifecycle;

public interface DisposableBean {
    void destroy() throws Exception;
}
