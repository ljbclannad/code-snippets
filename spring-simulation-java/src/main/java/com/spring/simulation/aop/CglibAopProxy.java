package com.spring.simulation.aop;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * CGLIB 代理实现 AOP（简化版）
 *
 * CGLIB vs JDK 动态代理：
 * - JDK 动态代理：只能代理实现了接口的类
 * - CGLIB：可以代理没有接口的类，通过继承目标类实现
 *
 * CGLIB 原理：
 * 1. 动态生成目标类的子类
 * 2. 重写父类的方法，在方法中添加拦截逻辑
 * 3. 通过 super.method() 调用父类的原始方法
 *
 * 本实现为简化版，使用 Java 动态代理模拟 CGLIB 的行为
 * 真实的 CGLIB 使用字节码生成技术（ASM）
 */
public class CglibAopProxy {
    private Object target;
    private List<MethodInterceptor> interceptors = new ArrayList<>();

    public CglibAopProxy(Object target) {
        this.target = target;
    }

    public void addInterceptor(MethodInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    /**
     * 创建 CGLIB 代理对象
     *
     * 简化实现说明：
     * 由于 Java 标准库不包含字节码生成功能，这里使用动态代理模拟
     * 真实的 CGLIB 会：
     * 1. 使用 ASM 生成目标类的子类字节码
     * 2. 重写所有 public/protected 方法
     * 3. 在重写的方法中调用 MethodInterceptor
     */
    public Object getProxy() {
        System.out.println("[AOP] Creating CGLIB proxy for: " + target.getClass().getSimpleName());

        // 如果目标类实现了接口，使用 JDK 动态代理
        if (target.getClass().getInterfaces().length > 0) {
            return java.lang.reflect.Proxy.newProxyInstance(
                    target.getClass().getClassLoader(),
                    target.getClass().getInterfaces(),
                    (proxy, method, args) -> invokeMethod(method, args));
        }

        // 如果没有接口，返回包装后的对象（简化实现）
        // 真实的 CGLIB 会生成子类
        System.out.println("[AOP] Warning: Simplified CGLIB implementation - returning wrapped target");
        return new CglibProxyWrapper(target, this);
    }

    /**
     * 方法调用拦截
     */
    private Object invokeMethod(Method method, Object[] args) throws Throwable {
        if (interceptors.isEmpty()) {
            return method.invoke(target, args);
        }

        MethodInvocation invocation = new CglibMethodInvocation(target, method, args, interceptors);
        return invocation.proceed();
    }

    /**
     * CGLIB 代理包装类（简化实现）
     */
    private static class CglibProxyWrapper {
        private Object target;
        private CglibAopProxy proxy;

        public CglibProxyWrapper(Object target, CglibAopProxy proxy) {
            this.target = target;
            this.proxy = proxy;
        }

        public Object getTarget() {
            return target;
        }

        public Object invoke(Method method, Object[] args) throws Throwable {
            return proxy.invokeMethod(method, args);
        }
    }

    /**
     * CGLIB 方法调用链
     */
    private static class CglibMethodInvocation implements MethodInvocation {
        private Object target;
        private Method method;
        private Object[] arguments;
        private List<MethodInterceptor> interceptors;
        private int currentInterceptorIndex = -1;

        public CglibMethodInvocation(Object target, Method method, Object[] arguments,
                List<MethodInterceptor> interceptors) {
            this.target = target;
            this.method = method;
            this.arguments = arguments;
            this.interceptors = interceptors;
        }

        @Override
        public Object proceed() throws Throwable {
            if (currentInterceptorIndex == interceptors.size() - 1) {
                return method.invoke(target, arguments);
            }

            currentInterceptorIndex++;
            MethodInterceptor interceptor = interceptors.get(currentInterceptorIndex);
            return interceptor.invoke(null, method, arguments, this);
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getArguments() {
            return arguments;
        }

        @Override
        public Object getTarget() {
            return target;
        }
    }
}
