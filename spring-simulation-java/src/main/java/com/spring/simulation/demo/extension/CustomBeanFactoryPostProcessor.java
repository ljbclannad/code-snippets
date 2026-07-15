package com.spring.simulation.demo.extension;

import com.spring.simulation.ioc.factory.BeanDefinition;
import com.spring.simulation.ioc.factory.BeanFactory;
import com.spring.simulation.ioc.factory.BeanFactoryPostProcessor;

public class CustomBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    @Override
    public void postProcessBeanFactory(BeanFactory beanFactory) throws Exception {
        System.out.println("   [BeanFactoryPostProcessor] Modifying bean definitions...");
        for (BeanDefinition bd : beanFactory.getBeanDefinitions().values()) {
            System.out.println("      - Found bean: " + bd.getBeanName() + " (scope: " + bd.getScope() + ")");
        }
    }
}
