package com.spring.simulation.aop;

import java.lang.reflect.Method;

public class BeforeAdvice implements MethodInterceptor {
    private String adviceName;

    public BeforeAdvice(String adviceName) {
        this.adviceName = adviceName;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
        System.out.println("[AOP-Before] " + adviceName + " - Before method: " + method.getName());
        return invocation.proceed();
    }
}
