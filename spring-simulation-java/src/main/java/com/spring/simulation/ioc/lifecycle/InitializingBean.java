package com.spring.simulation.ioc.lifecycle;

public interface InitializingBean {
    void afterPropertiesSet() throws Exception;
}
