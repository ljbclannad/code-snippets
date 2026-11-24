package com.spring.simulation.ioc.factory;

/** Early references must have the same identity as the final exposed singleton. */
public interface SmartInstantiationAwareBeanPostProcessor extends BeanPostProcessor {
    Object getEarlyBeanReference(Object bean, String beanName) throws Exception;
    default void clearEarlyReference(String beanName) {}
}
