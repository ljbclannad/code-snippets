package com.spring.simulation.ioc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 配置类注解
 *
 * 标记一个类为配置类，类似于 XML 配置文件
 * 配置类中可以使用 @Bean 注解定义 Bean
 *
 * 使用场景：
 * 1. Java 配置替代 XML 配置
 * 2. 第三方库的 Bean 配置（无法添加 @Component）
 * 3. 需要编程式创建 Bean 的场景
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Configuration {
    String value() default "";
}
