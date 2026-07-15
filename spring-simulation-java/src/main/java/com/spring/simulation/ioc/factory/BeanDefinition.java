package com.spring.simulation.ioc.factory;

import java.lang.reflect.Method;

import com.spring.simulation.ioc.scope.BeanScope;

/**
 * Bean 定义，存储 Bean 的元数据
 */
public class BeanDefinition {
    private String beanName;
    private Class<?> beanClass;
    private String scope = BeanScope.SINGLETON;

    // 用于 @Bean 方法
    private Method factoryMethod;
    private String factoryBeanName;

    public BeanDefinition(String beanName, Class<?> beanClass) {
        this.beanName = beanName;
        this.beanClass = beanClass;
    }

    public String getBeanName() {
        return beanName;
    }

    public Class<?> getBeanClass() {
        return beanClass;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public boolean isSingleton() {
        return BeanScope.SINGLETON.equals(scope);
    }

    public boolean isPrototype() {
        return BeanScope.PROTOTYPE.equals(scope);
    }

    public Method getFactoryMethod() {
        return factoryMethod;
    }

    public void setFactoryMethod(Method factoryMethod) {
        this.factoryMethod = factoryMethod;
    }

    public String getFactoryBeanName() {
        return factoryBeanName;
    }

    public void setFactoryBeanName(String factoryBeanName) {
        this.factoryBeanName = factoryBeanName;
    }
}
