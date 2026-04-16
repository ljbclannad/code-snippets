package com.spring.simulation.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Pointcut 注解
 *
 * 定义切点表达式，指定在哪些方法上应用通知
 *
 * 支持的表达式：
 * - execution: 匹配方法执行
 * - within: 匹配类型
 * - @annotation: 匹配带有指定注解的方法
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Pointcut {
    String value();
}
