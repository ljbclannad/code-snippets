package com.spring.simulation.spel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Value 注解
 *
 * 用于注入配置值或 SpEL 表达式结果
 *
 * 使用场景：
 * 1. 注入配置文件中的值：@Value("${server.port}")
 * 2. 注入 SpEL 表达式结果：@Value("#{systemProperties['user.name']}")
 * 3. 注入字面量：@Value("default value")
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Value {
    String value();
}
