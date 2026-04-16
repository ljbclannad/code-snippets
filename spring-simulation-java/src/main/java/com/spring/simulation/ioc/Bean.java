package com.spring.simulation.ioc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean 定义注解
 *
 * 用于在 @Configuration 类中定义 Bean
 * 方法的返回值会被注册为 Bean
 *
 * 使用场景：
 * 1. 第三方库的 Bean 配置
 * 2. 需要复杂初始化逻辑的 Bean
 * 3. 条件化创建 Bean
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
    /**
     * Bean 名称，默认为方法名
     */
    String value() default "";

    /**
     * Bean 作用域
     */
    String scope() default BeanScope.SINGLETON;
}
