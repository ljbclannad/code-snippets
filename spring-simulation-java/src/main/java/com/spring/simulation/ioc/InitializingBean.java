package com.spring.simulation.ioc;

public interface InitializingBean {
    void afterPropertiesSet() throws Exception;
}
