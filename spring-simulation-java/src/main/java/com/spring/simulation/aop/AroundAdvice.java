package com.spring.simulation.aop;

import java.lang.reflect.Method;

/**
 * Around 通知演示
 *
 * 关键问题：为什么 Around 会打印 2 次？
 * 答案：因为 Around 通知在 invocation.proceed() 前后都有逻辑！
 *
 * 执行流程：
 * 1. Around before（proceed 之前）
 * 2. invocation.proceed() → 触发下一个拦截器或目标方法
 * 3. Around after（proceed 之后）
 *
 * 这就是 Around 的核心：可以在目标方法执行前后都添加逻辑
 */
public class AroundAdvice implements MethodInterceptor {
    private String adviceName;

    public AroundAdvice(String adviceName) {
        this.adviceName = adviceName;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
        long startTime = System.currentTimeMillis();

        // ========== Around Before ==========
        // 这是第 1 次打印：在调用 proceed() 之前
        System.out.println("[AOP-Around] " + adviceName + " - Around before: " + method.getName());

        try {
            // ========== 关键点：调用 proceed() ==========
            // 这里会触发：
            // 1. 下一个拦截器的执行（如果还有）
            // 2. 或者目标方法的执行（如果是最后一个拦截器）
            Object result = invocation.proceed();

            // ========== Around After (正常返回) ==========
            // 这是第 2 次打印：在 proceed() 返回之后
            long endTime = System.currentTimeMillis();
            System.out.println("[AOP-Around] " + adviceName + " - Around after: " + method.getName() +
                             " (took " + (endTime - startTime) + "ms)");
            return result;
        } catch (Throwable e) {
            // ========== Around After (异常返回) ==========
            // 如果 proceed() 抛出异常，会执行这里
            System.out.println("[AOP-Around] " + adviceName + " - Exception in: " + method.getName());
            throw e;
        }
    }
}
