package com.spring.simulation.aop;

import java.lang.reflect.Method;

public interface MethodInterceptor {
    Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable;
}
