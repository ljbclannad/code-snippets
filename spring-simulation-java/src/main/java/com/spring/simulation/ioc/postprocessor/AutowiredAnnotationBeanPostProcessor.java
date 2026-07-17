package com.spring.simulation.ioc.postprocessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.annotation.Qualifier;
import com.spring.simulation.ioc.factory.BeanFactory;
import com.spring.simulation.ioc.factory.InstantiationAwareBeanPostProcessor;

/**
 * 容器内建的 {@code @Autowired} 注入处理器。
 *
 * <p>在属性填充阶段执行，因此注入完成后才会进入 Aware、初始化回调和普通
 * {@code BeanPostProcessor} 链路。</p>
 */
public class AutowiredAnnotationBeanPostProcessor implements InstantiationAwareBeanPostProcessor {
    private final BeanFactory beanFactory;

    public AutowiredAnnotationBeanPostProcessor(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void postProcessProperties(Object bean, String beanName) throws Exception {
        injectFields(bean, beanName);
        injectMethods(bean, beanName);
    }

    private void injectFields(Object bean, String beanName) throws Exception {
        for (Field field : bean.getClass().getDeclaredFields()) {
            if (!field.isAnnotationPresent(Autowired.class)) {
                continue;
            }
            field.setAccessible(true);
            field.set(bean, beanFactory.resolveDependency(field.getType(), qualifierValue(field.getAnnotation(Qualifier.class))));
            System.out.println("   [Autowired] Injected " + field.getType().getSimpleName() + " into "
                    + beanName + " (field: " + field.getName() + ")");
        }
    }

    private void injectMethods(Object bean, String beanName) throws Exception {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Autowired.class)) {
                continue;
            }
            if (method.getParameterCount() == 0) {
                throw new IllegalStateException("@Autowired method must declare at least one parameter: "
                        + beanName + "." + method.getName());
            }

            Parameter[] parameters = method.getParameters();
            Object[] arguments = new Object[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                arguments[index] = beanFactory.resolveDependency(parameters[index].getType(),
                        qualifierValue(parameters[index].getAnnotation(Qualifier.class)));
            }
            method.setAccessible(true);
            method.invoke(bean, arguments);
            System.out.println("   [Autowired] Injected " + parameters.length + " dependency/dependencies into "
                    + beanName + " (method: " + method.getName() + ")");
        }
    }

    private String qualifierValue(Qualifier qualifier) {
        return qualifier == null ? null : qualifier.value();
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
