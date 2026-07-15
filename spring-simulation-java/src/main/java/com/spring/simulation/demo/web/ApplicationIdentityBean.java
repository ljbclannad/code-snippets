package com.spring.simulation.demo.web;

import java.util.concurrent.atomic.AtomicInteger;

import com.spring.simulation.ioc.annotation.Component;
import com.spring.simulation.ioc.annotation.Scope;
import com.spring.simulation.ioc.scope.BeanScope;

@Component
@Scope(BeanScope.APPLICATION)
public class ApplicationIdentityBean implements ApplicationIdentity {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private final String value = "application-" + SEQUENCE.incrementAndGet();

    @Override
    public String getValue() {
        return value;
    }
}
