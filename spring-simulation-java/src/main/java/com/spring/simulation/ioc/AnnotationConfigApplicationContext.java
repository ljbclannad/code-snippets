package com.spring.simulation.ioc;

import java.lang.reflect.Method;

public class AnnotationConfigApplicationContext extends ApplicationContext {
    private final String basePackage;

    public AnnotationConfigApplicationContext(String basePackage) {
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
    protected void scanAndRegisterBeanDefinitions(String basePackage) {
        try {
            ComponentScanner scanner = new ComponentScanner(basePackage);
            for (Class<?> componentClass : scanner.scan()) {
                BeanDefinition beanDefinition = createBeanDefinition(componentClass);
                String beanName = beanDefinition.getBeanName();
                registerBeanDefinition(beanName, beanDefinition);

                // 处理 @Configuration 类中的 @Bean 方法
                if (componentClass.isAnnotationPresent(Configuration.class)) {
                    processBeanMethods(componentClass, beanName);
                }
            }
        } catch (Exception e) {
            System.err.println("Error scanning components: " + e.getMessage());
        }
    }

    /**
     * 将扫描到的组件类转换为 BeanDefinition。
     */
    protected BeanDefinition createBeanDefinition(Class<?> componentClass) {
        Component annotation = componentClass.getAnnotation(Component.class);
        String beanName = annotation.value().isEmpty()
                ? generateBeanName(componentClass)
                : annotation.value();

        BeanDefinition beanDefinition = new BeanDefinition(beanName, componentClass);
        if (componentClass.isAnnotationPresent(Scope.class)) {
            String scope = componentClass.getAnnotation(Scope.class).value();
            beanDefinition.setScope(scope);
        }
        return beanDefinition;
    }

    /**
     * 处理 @Configuration 类中的 @Bean 方法
     */
    private void processBeanMethods(Class<?> configClass, String configBeanName) {
        for (Method method : configClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class)) {
                Bean beanAnnotation = method.getAnnotation(Bean.class);
                String beanName = beanAnnotation.value().isEmpty() ?
                        method.getName() : beanAnnotation.value();

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
