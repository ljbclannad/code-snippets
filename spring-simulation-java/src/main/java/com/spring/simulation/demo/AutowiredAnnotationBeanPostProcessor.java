package com.spring.simulation.demo;

import com.spring.simulation.ioc.BeanPostProcessor;

/**
 * 模拟 Spring 的 AutowiredAnnotationBeanPostProcessor
 * 用于处理 @Autowired、@Value 等注解的依赖注入
 *
 * 这是一个 Regular 优先级的 BeanPostProcessor
 */
public class AutowiredAnnotationBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws Exception {
        System.out.println("   [AutowiredAnnotationBeanPostProcessor] Processing @Autowired for: " + beanName);
        // 实际实现会扫描字段和方法上的 @Autowired 注解，并注入依赖
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws Exception {
        // @Autowired 处理在 before 阶段完成，after 阶段不需要处理
        return bean;
    }
}
