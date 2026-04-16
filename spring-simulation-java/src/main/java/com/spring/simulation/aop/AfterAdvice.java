package com.spring.simulation.aop;

import java.lang.reflect.Method;

public class AfterAdvice implements MethodInterceptor {
    private String adviceName;

    public AfterAdvice(String adviceName) {
        this.adviceName = adviceName;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
        try {
            return invocation.proceed();
        } finally {
            System.out.println("[AOP-After] " + adviceName + " - After method: " + method.getName());
        }
    }
}
