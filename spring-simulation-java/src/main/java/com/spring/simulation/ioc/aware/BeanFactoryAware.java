package com.spring.simulation.ioc.aware;

import com.spring.simulation.ioc.factory.BeanFactory;

public interface BeanFactoryAware {
    void setBeanFactory(BeanFactory beanFactory);
}
