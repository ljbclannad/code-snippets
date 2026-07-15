package com.spring.simulation.demo.web;

import java.util.concurrent.atomic.AtomicInteger;

import com.spring.simulation.ioc.annotation.Component;
import com.spring.simulation.ioc.annotation.Scope;
import com.spring.simulation.ioc.scope.BeanScope;

@Component
@Scope(BeanScope.REQUEST)
public class RequestIdentityBean implements RequestIdentity {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private final String value = "request-" + SEQUENCE.incrementAndGet();

    @Override
    public String getValue() {
        return value;
    }
}
