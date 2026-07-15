package com.spring.simulation.ioc.context;

import java.lang.reflect.Method;

import com.spring.simulation.ioc.annotation.Bean;
import com.spring.simulation.ioc.annotation.Component;
import com.spring.simulation.ioc.annotation.Configuration;
import com.spring.simulation.ioc.annotation.Scope;
import com.spring.simulation.ioc.factory.BeanDefinition;
import com.spring.simulation.ioc.scanner.ComponentScanner;

public class AnnotationConfigApplicationContext extends ApplicationContext {
    private final String[] basePackage;

    public AnnotationConfigApplicationContext(String... basePackage) {
        this.basePackage = basePackage;
    }

    @Override
    protected void loadBeanDefinitions() throws Exception {
        System.out.println("  - Loading bean definitions from annotation config");
        scanAndRegisterBeanDefinitions(basePackage);
    }

    /**
     * 注解配置上下文的定义加载入口：
     * 1. 组件扫描
     * 2. 将扫描结果转换为 BeanDefinition
     * 3. 注册到 BeanFactory
     * 4. 处理 @Configuration 中的 @Bean 方法
     */
    protected void scanAndRegisterBeanDefinitions(String[] basePackage) {
        try {
            ComponentScanner scanner = new ComponentScanner(basePackage);
            for (Class<?> beanClass : scanner.scan()) {
                BeanDefinition beanDefinition = createBeanDefinition(beanClass);
                String beanName = beanDefinition.getBeanName();
                // 将扫描到的内容注册到beanFactory中
                registerBeanDefinition(beanName, beanDefinition);

                // 处理 @Configuration 类中的 @Bean 方法
                if (beanClass.isAnnotationPresent(Configuration.class)) {
                    processBeanMethods(beanClass, beanName);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to scan and register bean definitions: " + basePackage, e);
        }
    }

    /**
     * 将扫描到的组件类转换为 BeanDefinition。
     */
    protected BeanDefinition createBeanDefinition(Class<?> beanClass) {
        String beanName = getBeanName(beanClass);

        BeanDefinition beanDefinition = new BeanDefinition(beanName, beanClass);
        if (beanClass.isAnnotationPresent(Scope.class)) {
            String scope = beanClass.getAnnotation(Scope.class).value();
            beanDefinition.setScope(scope);
        }
        return beanDefinition;
    }

    /**
     * 获取注释的beanName
     * 
     * @param beanClass
     * @return
     */
    private String getBeanName(Class<?> beanClass) {
        Component component = beanClass.getAnnotation(Component.class);
        if (component != null && !component.value().isEmpty()) {
            return component.value();
        }

        Configuration configuration = beanClass.getAnnotation(Configuration.class);
        if (configuration != null && !configuration.value().isEmpty()) {
            return configuration.value();
        }

        return generateBeanName(beanClass);
    }

    /**
     * 处理 @Configuration 类中的 @Bean 方法
     */
    private void processBeanMethods(Class<?> configClass, String configBeanName) {
        for (Method method : configClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class)) {
                Bean beanAnnotation = method.getAnnotation(Bean.class);
                String beanName = beanAnnotation.value().isEmpty() ? method.getName() : beanAnnotation.value();

                BeanDefinition beanDefinition = new BeanDefinition(beanName, method.getReturnType());
                beanDefinition.setScope(beanAnnotation.scope());
                beanDefinition.setFactoryMethod(method);
                beanDefinition.setFactoryBeanName(configBeanName);

                registerBeanDefinition(beanName, beanDefinition);
                System.out.println("    - Registered @Bean method: " + method.getName() +
                        " -> " + beanName + " (scope: " + beanAnnotation.scope() + ")");
            }
        }
    }

    private String generateBeanName(Class<?> clazz) {
        String simpleName = clazz.getSimpleName();
        return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
    }
}
