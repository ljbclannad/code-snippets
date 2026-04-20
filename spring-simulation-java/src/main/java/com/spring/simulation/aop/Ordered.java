package com.spring.simulation.aop;

/**
 * Ordered 接口
 *
 * 用于控制拦截器的执行顺序
 * 数字越小，优先级越高，越先执行
 *
 * Spring AOP 中的应用：
 * - @Order(1) 的切面比 @Order(2) 的切面先执行
 * - 实现 Ordered 接口可以动态控制顺序
 */
public interface Ordered {
    /**
     * 最高优先级
     */
    int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;

    /**
     * 最低优先级
     */
    int LOWEST_PRECEDENCE = Integer.MAX_VALUE;

    /**
     * 获取顺序值
     * 数字越小，优先级越高
     *
     * @return 顺序值
     */
    int getOrder();
}
