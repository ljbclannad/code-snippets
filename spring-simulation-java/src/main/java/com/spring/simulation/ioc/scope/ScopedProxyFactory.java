package com.spring.simulation.ioc.scope;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * 为较短生命周期的 Bean 创建接口代理。
 *
 * 代理自身可安全注入 singleton Bean；每次业务方法调用时才从当前 Scope 取得真实目标。
 */
public final class ScopedProxyFactory {
    private ScopedProxyFactory() {
    }

    public static Object createProxy(Class<?> dependencyType, String beanName, Supplier<Object> targetProvider) {
        if (!dependencyType.isInterface()) {
            throw new IllegalStateException("Scoped proxy for bean '" + beanName
                    + "' requires an interface injection type; class-based proxies are not implemented");
        }

        return Proxy.newProxyInstance(
                dependencyType.getClassLoader(),
                new Class<?>[] { dependencyType },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) {
                            return "ScopedProxy(" + beanName + ")";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                    }

                    Object target = targetProvider.get();
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }
}
