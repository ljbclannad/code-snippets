package com.spring.simulation.aop;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * JDK 动态代理实现 AOP
 *
 * 为什么使用 JDK 动态代理？
 * 1. 无需修改目标类的代码，通过代理对象拦截方法调用
 * 2. 可以在方法执行前后添加额外逻辑（如事务、日志、性能监控）
 * 3. 对调用者透明，调用者不知道自己使用的是代理对象
 *
 * JDK 动态代理的限制：
 * - 只能代理实现了接口的类（因为代理类需要实现相同的接口）
 * - 如果目标类没有接口，需要使用 CGLIB 代理（本项目未实现）
 *
 * Spring AOP 的实现原理：
 * 1. 在 BeanPostProcessor.postProcessAfterInitialization 中创建代理
 * 2. 将原始 Bean 替换为代理对象
 * 3. 所有对 Bean 的方法调用都会被代理拦截
 */
public class JdkDynamicAopProxy implements InvocationHandler {
    // 目标对象（被代理的真实对象）
    private Object target;

    // 拦截器链：多个拦截器按顺序执行（责任链模式）
    // 设计原因：支持多个切面（如事务、日志、性能监控）同时作用于一个方法
    private List<MethodInterceptor> interceptors = new ArrayList<>();

    public JdkDynamicAopProxy(Object target) {
        this.target = target;
    }

    /**
     * 添加拦截器
     * 注意：添加后需要调用 sortInterceptors() 来按 Ordered 排序
     */
    public void addInterceptor(MethodInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    /**
     * 对拦截器进行排序（按 Ordered 接口）
     *
     * 排序规则：
     * 1. PriorityOrdered > Ordered > 普通（没有实现接口）
     * 2. 在同一优先级内，按 getOrder() 值排序（数字越小越先执行）
     *
     * 这个方法在 Spring AOP 中的生效时机：
     * - 在创建代理对象之前调用
     * - 确保拦截器按正确的顺序执行
     */
    public void sortInterceptors() {
        interceptors.sort(new Comparator<MethodInterceptor>() {
            @Override
            public int compare(MethodInterceptor o1, MethodInterceptor o2) {
                // 步骤 1: 判断优先级类别
                int priority1 = getPriorityLevel(o1);
                int priority2 = getPriorityLevel(o2);

                if (priority1 != priority2) {
                    // 优先级不同，按优先级排序（数字越小越先执行）
                    return Integer.compare(priority1, priority2);
                }

                // 步骤 2: 优先级相同，按 Order 值排序
                int order1 = getOrder(o1);
                int order2 = getOrder(o2);
                return Integer.compare(order1, order2);
            }

            /**
             * 获取优先级级别
             * 1 = PriorityOrdered
             * 2 = Ordered
             * 3 = 普通（没有实现接口）
             */
            private int getPriorityLevel(MethodInterceptor interceptor) {
                if (interceptor instanceof PriorityOrdered) {
                    return 1;
                } else if (interceptor instanceof Ordered) {
                    return 2;
                } else {
                    return 3;
                }
            }

            /**
             * 获取 Order 值
             */
            private int getOrder(MethodInterceptor interceptor) {
                if (interceptor instanceof Ordered) {
                    return ((Ordered) interceptor).getOrder();
                }
                return Ordered.LOWEST_PRECEDENCE;  // 默认最低优先级
            }
        });

        System.out.println("[AOP] Interceptors sorted by order:");
        for (int i = 0; i < interceptors.size(); i++) {
            MethodInterceptor interceptor = interceptors.get(i);
            String orderInfo = "";
            if (interceptor instanceof PriorityOrdered) {
                orderInfo = " [PriorityOrdered, order=" + ((PriorityOrdered) interceptor).getOrder() + "]";
            } else if (interceptor instanceof Ordered) {
                orderInfo = " [Ordered, order=" + ((Ordered) interceptor).getOrder() + "]";
            } else {
                orderInfo = " [Regular, no order]";
            }
            System.out.println("  " + (i + 1) + ". " + interceptor.getClass().getSimpleName() + orderInfo);
        }
    }

    /**
     * 创建代理对象
     *
     * JDK 动态代理的三要素：
     * 1. ClassLoader：用于加载代理类
     * 2. Interfaces：代理类需要实现的接口（必须有接口）
     * 3. InvocationHandler：方法调用处理器（this）
     *
     * 返回的代理对象：
     * - 实现了目标对象的所有接口
     * - 所有方法调用都会转发到 InvocationHandler.invoke()
     */
    public Object getProxy() {
        System.out.println("[AOP] Creating JDK dynamic proxy for: " + target.getClass().getSimpleName());
        return Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            target.getClass().getInterfaces(),
            this
        );
    }

    /**
     * 方法调用拦截（AOP 的核心）
     *
     * 当调用代理对象的任何方法时，都会执行这个 invoke 方法
     *
     * 参数说明：
     * @param proxy 代理对象本身（很少使用）
     * @param method 被调用的方法
     * @param args 方法参数
     *
     * 执行流程：
     * 1. 如果没有拦截器，直接调用目标方法
     * 2. 如果有拦截器，创建拦截器链并执行
     *
     * 设计原因：
     * - 将拦截逻辑委托给拦截器链，保持代理类的简洁
     * - 支持多个拦截器的组合（责任链模式）
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (interceptors.isEmpty()) {
            // 没有拦截器，直接调用目标方法
            return method.invoke(target, args);
        }

        // 创建方法调用链，按顺序执行所有拦截器
        MethodInvocation invocation = new ReflectiveMethodInvocation(target, method, args, interceptors);
        return invocation.proceed();
    }

    /**
     * 方法调用链的实现（责任链模式）
     *
     * 设计原因：
     * 1. 支持多个拦截器按顺序执行
     * 2. 每个拦截器可以决定是否继续执行下一个拦截器
     * 3. 拦截器可以在目标方法执行前后添加逻辑
     *
     * 执行顺序示例（3 个拦截器）：
     * Interceptor1.before
     *   Interceptor2.before
     *     Interceptor3.before
     *       目标方法执行
     *     Interceptor3.after
     *   Interceptor2.after
     * Interceptor1.after
     *
     * 这就是 Spring AOP 中 @Before、@Around、@After 的执行原理
     */
    private static class ReflectiveMethodInvocation implements MethodInvocation {
        private Object target;
        private Method method;
        private Object[] arguments;
        private List<MethodInterceptor> interceptors;

        // 当前执行到第几个拦截器（-1 表示还未开始）
        // 设计原因：通过索引控制拦截器的执行顺序
        private int currentInterceptorIndex = -1;

        public ReflectiveMethodInvocation(Object target, Method method, Object[] arguments,
                                         List<MethodInterceptor> interceptors) {
            this.target = target;
            this.method = method;
            this.arguments = arguments;
            this.interceptors = interceptors;
        }

        /**
         * 执行下一个拦截器或目标方法（责任链的核心）
         *
         * 执行逻辑：
         * 1. 如果所有拦截器都执行完了，调用目标方法
         * 2. 否则，执行下一个拦截器
         *
         * 关键设计：
         * - 每个拦截器内部会调用 invocation.proceed() 来触发下一个拦截器
         * - 拦截器可以在 proceed() 前后添加逻辑（实现 @Before、@After）
         * - 拦截器可以决定是否调用 proceed()（实现条件拦截）
         *
         * 示例：
         * interceptor.invoke() {
         *     // before 逻辑
         *     Object result = invocation.proceed(); // 触发下一个拦截器或目标方法
         *     // after 逻辑
         *     return result;
         * }
         */
        @Override
        public Object proceed() throws Throwable {
            // 所有拦截器都执行完了，调用目标方法
            if (currentInterceptorIndex == interceptors.size() - 1) {
                return method.invoke(target, arguments);
            }

            // 执行下一个拦截器
            currentInterceptorIndex++;
            MethodInterceptor interceptor = interceptors.get(currentInterceptorIndex);
            // 将 this 传给拦截器，拦截器可以调用 proceed() 继续执行链
            return interceptor.invoke(null, method, arguments, this);
        }

        @Override
        public Method getMethod() {
            return method;
        }

        @Override
        public Object[] getArguments() {
            return arguments;
        }

        @Override
        public Object getTarget() {
            return target;
        }
    }
}
