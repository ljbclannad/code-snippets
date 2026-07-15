package com.spring.simulation.ioc.scope;

import java.util.function.Supplier;

/**
 * session 作用域：同一会话内按 Bean 名称复用实例，会话失效时由上下文清理。
 */
public class SessionScope implements BeanScopeHandler {

    @Override
    public Object get(String beanName, Supplier<Object> objectFactory) {
        return SessionScopeContextHolder.currentSessionBeans()
                .computeIfAbsent(beanName, ignored -> objectFactory.get());
    }
}
