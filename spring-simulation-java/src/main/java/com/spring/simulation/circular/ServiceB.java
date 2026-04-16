package com.spring.simulation.circular;

import com.spring.simulation.ioc.Autowired;
import com.spring.simulation.ioc.Component;

@Component
public class ServiceB {
    @Autowired
    private ServiceA serviceA;

    public void doSomethingElse() {
        System.out.println("    [ServiceB] Doing something else, calling ServiceA...");
        serviceA.methodA();
    }

    public void methodB() {
        System.out.println("    [ServiceB] Method B executed");
    }
}
