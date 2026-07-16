package com.spring.simulation.aop;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * 将一个 @Aspect 中的 @Before、@Around 或 @After 方法适配为拦截器。
 */
public class AspectMethodInterceptor implements MethodInterceptor {
    public enum AdviceType {
        BEFORE, AROUND, AFTER
    }

    private final Object aspect;
    private final Method adviceMethod;
    private final String pointcutExpression;
    private final AdviceType adviceType;
    private final PointcutExpressionParser pointcutParser = new PointcutExpressionParser();

    public AspectMethodInterceptor(Object aspect, Method adviceMethod,
            String pointcutExpression, AdviceType adviceType) {
        this.aspect = aspect;
        this.adviceMethod = adviceMethod;
        this.pointcutExpression = pointcutExpression;
        this.adviceType = adviceType;
        this.adviceMethod.setAccessible(true);
    }

    public boolean matchesAnyMethod(Class<?> targetClass) {
        for (Method method : targetClass.getMethods()) {
            if (pointcutParser.matches(pointcutExpression, method, targetClass)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
        Method targetMethod = resolveTargetMethod(method, invocation.getTarget().getClass());
        if (!pointcutParser.matches(pointcutExpression, targetMethod, invocation.getTarget().getClass())) {
            return invocation.proceed();
        }

        switch (adviceType) {
            case BEFORE:
                invokeAdvice(invocation);
                return invocation.proceed();
            case AFTER:
                try {
                    return invocation.proceed();
                } finally {
                    invokeAdvice(invocation);
                }
            case AROUND:
                return invokeAroundAdvice(invocation);
            default:
                return invocation.proceed();
        }
    }

    private Method resolveTargetMethod(Method invokedMethod, Class<?> targetClass) {
        try {
            return targetClass.getMethod(invokedMethod.getName(), invokedMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return invokedMethod;
        }
    }

    private Object invokeAroundAdvice(MethodInvocation invocation) throws Throwable {
        if (adviceMethod.getParameterCount() != 1
                || !MethodInvocation.class.isAssignableFrom(adviceMethod.getParameterTypes()[0])) {
            throw new IllegalStateException("@Around advice must declare one MethodInvocation parameter: "
                    + adviceMethod);
        }
        return invokeReflectively(invocation);
    }

    private void invokeAdvice(MethodInvocation invocation) throws Throwable {
        if (adviceMethod.getParameterCount() == 0) {
            invokeReflectively();
            return;
        }
        if (adviceMethod.getParameterCount() == 1
                && MethodInvocation.class.isAssignableFrom(adviceMethod.getParameterTypes()[0])) {
            invokeReflectively(invocation);
            return;
        }
        throw new IllegalStateException("@Before/@After advice must have no parameter or one MethodInvocation parameter: "
                + adviceMethod);
    }

    private Object invokeReflectively(Object... args) throws Throwable {
        try {
            return adviceMethod.invoke(aspect, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
