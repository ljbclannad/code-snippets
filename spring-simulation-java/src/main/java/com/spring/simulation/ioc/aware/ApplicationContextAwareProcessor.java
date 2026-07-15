package com.spring.simulation.ioc.aware;

import com.spring.simulation.ioc.context.ApplicationContext;
import com.spring.simulation.ioc.factory.BeanPostProcessor;

/**
 * 在 Bean 初始化前处理 ApplicationContextAware 回调。
 *
 * 由 ApplicationContext 在 prepareBeanFactory() 阶段提前注册，
 * 避免 BeanFactory 直接依赖 ApplicationContext 的具体回调逻辑。
 */
public class ApplicationContextAwareProcessor implements BeanPostProcessor {
    private final ApplicationContext applicationContext;

    public ApplicationContextAwareProcessor(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (bean instanceof ApplicationContextAware) {
            System.out.println("   [Aware] Invoking ApplicationContextAware.setApplicationContext");
            ((ApplicationContextAware) bean).setApplicationContext(applicationContext);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
