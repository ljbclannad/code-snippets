package com.spring.simulation.ioc.scope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * application 作用域：一个 Web ApplicationContext 内按 Bean 名称共享实例。
 *
 * 每个 Web 上下文注册自己的 ApplicationScope，因此不同 Web 应用不会共享对象。
 */
public class ApplicationScope implements BeanScopeHandler {
    private final Map<String, Object> applicationBeans = new ConcurrentHashMap<>();

    @Override
    public Object get(String beanName, Supplier<Object> objectFactory) {
        return applicationBeans.computeIfAbsent(beanName, ignored -> objectFactory.get());
    }
}
