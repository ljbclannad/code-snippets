package com.spring.simulation.aop;

import java.lang.reflect.Method;

public class AroundAdvice implements MethodInterceptor {
    private String adviceName;

    public AroundAdvice(String adviceName) {
        this.adviceName = adviceName;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
        long startTime = System.currentTimeMillis();
        System.out.println("[AOP-Around] " + adviceName + " - Around before: " + method.getName());

        try {
            Object result = invocation.proceed();
            long endTime = System.currentTimeMillis();
            System.out.println("[AOP-Around] " + adviceName + " - Around after: " + method.getName() +
                             " (took " + (endTime - startTime) + "ms)");
            return result;
        } catch (Throwable e) {
            System.out.println("[AOP-Around] " + adviceName + " - Exception in: " + method.getName());
            throw e;
        }
    }
}
