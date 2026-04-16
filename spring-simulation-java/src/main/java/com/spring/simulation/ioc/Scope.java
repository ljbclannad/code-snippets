package com.spring.simulation.ioc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bean 作用域注解
 *
 * Spring 支持的作用域：
 * - singleton: 单例（默认），整个容器只有一个实例
 * - prototype: 原型，每次获取都创建新实例
 * - request: 请求作用域（Web 环境），每个 HTTP 请求一个实例
 * - session: 会话作用域（Web 环境），每个 HTTP 会话一个实例
 * - application: 应用作用域（Web 环境），整个 ServletContext 一个实例
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Scope {
    String value() default "singleton";
}
