package com.lx.governance.common.monitoring.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 监控注解，用于自动记录方法执行时间和成功率
 *
 * 使用示例：
 * <pre>
 * {@code
 * @Monitored(value = "order.create", description = "订单创建")
 * public Order createOrder(CreateOrderRequest request) {
 *     // 业务逻辑
 * }
 * }
 * </pre>
 *
 * 生成的指标：
 * - {value}.duration: 方法执行时间（Timer）
 * - {value}.total: 方法调用次数（Counter），带 status 标签（success/failure）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {

    /**
     * 指标名称（必填）
     * 例如：order.create, payment.confirm
     */
    String value();

    /**
     * 指标描述
     */
    String description() default "";

    /**
     * 是否记录失败次数
     * 默认为 true，会记录异常类型
     */
    boolean recordFailures() default true;
}
