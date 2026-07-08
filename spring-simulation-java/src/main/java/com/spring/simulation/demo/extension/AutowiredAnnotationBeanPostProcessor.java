package com.spring.simulation.demo.extension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.spring.simulation.ioc.Autowired;
import com.spring.simulation.ioc.BeanFactory;
import com.spring.simulation.ioc.BeanPostProcessor;

/**
 * 模拟 Spring 的 AutowiredAnnotationBeanPostProcessor
 * 用于处理 @Autowired、@Value 等注解的依赖注入
 *
 * 核心职责：
 * 1. 扫描 Bean 的字段和方法上的 @Autowired 注解
 * 2. 从容器中查找对应的依赖 Bean
 * 3. 通过反射注入依赖
 *
 * 这是一个 Regular 优先级的 BeanPostProcessor
 */
public class AutowiredAnnotationBeanPostProcessor implements BeanPostProcessor {

    private BeanFactory beanFactory;

    public AutowiredAnnotationBeanPostProcessor(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws Exception {
        System.out.println("   [AutowiredAnnotationBeanPostProcessor] Processing @Autowired for: " + beanName);

        // 步骤 1: 处理字段注入（Field Injection）
        injectFields(bean, beanName);

        // 步骤 2: 处理方法注入（Setter Injection）
        injectMethods(bean, beanName);

        return bean;
    }

    /**
     * 处理字段注入
     * 扫描所有字段，找到标注了 @Autowired 的字段并注入依赖
     */
    private void injectFields(Object bean, String beanName) throws Exception {
        Class<?> clazz = bean.getClass();

        // 遍历所有字段（包括私有字段）
        for (Field field : clazz.getDeclaredFields()) {
            // 检查字段是否标注了 @Autowired
            if (field.isAnnotationPresent(Autowired.class)) {
                System.out.println("     - Found @Autowired field: " + field.getName() + " (type: "
                        + field.getType().getSimpleName() + ")");

                // 步骤 1: 确定要注入的 Bean 名称
                // 默认按类型查找，这里简化为按字段名查找
                String dependencyBeanName = field.getName();

                // 步骤 2: 从容器中获取依赖 Bean
                Object dependency = null;
                try {
                    dependency = beanFactory.getBean(dependencyBeanName);
                    System.out.println("       → Injecting bean: " + dependencyBeanName);
                } catch (Exception e) {
                    // 如果按名称找不到，尝试按类型查找
                    System.out
                            .println("       → Bean '" + dependencyBeanName + "' not found by name, trying by type...");
                    dependency = beanFactory.getBeanByType(field.getType());
                    if (dependency != null) {
                        System.out.println("       → Found by type: " + dependency.getClass().getSimpleName());
                    }
                }

                if (dependency == null) {
                    throw new RuntimeException("Cannot resolve dependency for field: " + field.getName() +
                            " in bean: " + beanName);
                }

                // 步骤 3: 通过反射注入依赖
                field.setAccessible(true); // 允许访问私有字段
                field.set(bean, dependency);
                System.out.println("       ✓ Injection completed");
            }
        }
    }

    /**
     * 处理方法注入（Setter 方法）
     * 扫描所有方法，找到标注了 @Autowired 的方法并调用
     */
    private void injectMethods(Object bean, String beanName) throws Exception {
        Class<?> clazz = bean.getClass();

        // 遍历所有方法
        for (Method method : clazz.getDeclaredMethods()) {
            // 检查方法是否标注了 @Autowired
            if (method.isAnnotationPresent(Autowired.class)) {
                System.out.println("     - Found @Autowired method: " + method.getName());

                // 获取方法参数类型
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 0) {
                    System.out.println("       ⚠ Method has no parameters, skipping");
                    continue;
                }

                // 步骤 1: 为每个参数查找依赖 Bean
                Object[] args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    Class<?> paramType = paramTypes[i];
                    System.out.println(
                            "       → Resolving parameter " + i + " (type: " + paramType.getSimpleName() + ")");

                    // 按类型查找依赖
                    Object dependency = beanFactory.getBeanByType(paramType);
                    if (dependency == null) {
                        throw new RuntimeException("Cannot resolve dependency for parameter " + i +
                                " of method: " + method.getName() + " in bean: " + beanName);
                    }

                    args[i] = dependency;
                    System.out.println("         ✓ Found: " + dependency.getClass().getSimpleName());
                }

                // 步骤 2: 调用方法注入依赖
                method.setAccessible(true);
                method.invoke(bean, args);
                System.out.println("       ✓ Method injection completed");
            }
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws Exception {
        // @Autowired 处理在 before 阶段完成，after 阶段不需要处理
        return bean;
    }
}
