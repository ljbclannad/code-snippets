package com.spring.simulation.circular;

import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.annotation.Component;

@Component
public class ServiceA {
    @Autowired
    private ServiceB serviceB;

    public void doSomething() {
        System.out.println("    [ServiceA] Doing something, calling ServiceB...");
        serviceB.doSomethingElse();
    }

    public void methodA() {
        System.out.println("    [ServiceA] Method A executed");
    }
}
