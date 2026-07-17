package com.spring.simulation.ioc.factory;

/**
 * 在 Bean 已实例化但尚未执行 Aware 回调和初始化方法前参与属性填充。
 */
public interface InstantiationAwareBeanPostProcessor extends BeanPostProcessor {
    void postProcessProperties(Object bean, String beanName) throws Exception;
}
