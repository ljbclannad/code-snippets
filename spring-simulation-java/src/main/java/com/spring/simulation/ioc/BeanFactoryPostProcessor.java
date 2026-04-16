package com.spring.simulation.ioc;

public interface BeanFactoryPostProcessor {
    void postProcessBeanFactory(BeanFactory beanFactory) throws Exception;
}
