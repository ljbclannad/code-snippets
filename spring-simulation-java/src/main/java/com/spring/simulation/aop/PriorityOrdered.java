package com.spring.simulation.aop;

/**
 * PriorityOrdered 接口
 *
 * 继承自 Ordered，表示更高优先级
 *
 * 优先级顺序：
 * PriorityOrdered > Ordered > 普通（没有实现任何接口）
 *
 * 在同一优先级内，再按 getOrder() 的值排序
 */
public interface PriorityOrdered extends Ordered {
}
