package com.spring.simulation.ioc.factory;

public interface BeanFactoryPostProcessor {
    void postProcessBeanFactory(BeanFactory beanFactory) throws Exception;
}
