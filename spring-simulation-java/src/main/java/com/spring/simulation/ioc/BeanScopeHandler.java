package com.spring.simulation.ioc;

import java.util.function.Supplier;

/**
 * BeanFactory 处理非单例作用域的运行时策略。
 *
 * 与 {@link Scope} 注解不同：注解只描述 BeanDefinition 的 scope 名称，
 * 此接口负责在 getBean() 时决定对象如何创建和缓存。
 */
public interface BeanScopeHandler {

    Object get(String beanName, Supplier<Object> objectFactory);
}
