package com.spring.simulation.demo.aop;

import com.spring.simulation.aop.After;
import com.spring.simulation.aop.Around;
import com.spring.simulation.aop.Aspect;
import com.spring.simulation.aop.Before;
import com.spring.simulation.aop.MethodInvocation;
import com.spring.simulation.aop.Pointcut;
import com.spring.simulation.ioc.annotation.Component;

/**
 * 供 SpringRefreshDemo 展示自动解析 @Aspect 通知的切面。
 */
@Component
@Aspect
public class DemoAuditAspect {
    @Pointcut("execution(* create*(..))")
    public void createOperations() {
    }

    @Before("createOperations()")
    public void beforeCreate() {
        System.out.println("   [Aspect-Before] Creating resource");
    }

    @After("createOperations()")
    public void afterCreate() {
        System.out.println("   [Aspect-After] Resource creation completed");
    }

    @Around("execution(* getUserInfo(..))")
    public Object measureGetUserInfo(MethodInvocation invocation) throws Throwable {
        System.out.println("   [Aspect-Around] Before " + invocation.getMethod().getName());
        Object result = invocation.proceed();
        System.out.println("   [Aspect-Around] After " + invocation.getMethod().getName());
        return result;
    }
}
