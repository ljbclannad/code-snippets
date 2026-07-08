package com.spring.simulation.examples.aop;

import com.spring.simulation.aop.*;

import java.lang.reflect.Method;

/**
 * AOP Ordered 排序演示
 *
 * 演示 Spring AOP 中 @Order 和 Ordered 接口如何控制拦截器的执行顺序
 */
public class AopOrderedDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== AOP Ordered 排序演示 ==========\n");

        // 创建目标对象
        UserService target = new UserServiceImpl();

        // 创建 AOP 代理
        JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(target);

        System.out.println("========== 场景 1: 不使用 Ordered（按添加顺序执行）==========\n");
        testWithoutOrdered(target);

        System.out.println("\n========== 场景 2: 使用 Ordered（按 Order 值排序执行）==========\n");
        testWithOrdered(target);
    }

    /**
     * 场景 1: 不使用 Ordered，按添加顺序执行
     */
    static void testWithoutOrdered(UserService target) throws Exception {
        JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(target);

        System.out.println("添加拦截器顺序：");
        System.out.println("  1. SecurityAdvice (安全检查)");
        System.out.println("  2. TransactionAdvice (事务管理)");
        System.out.println("  3. LoggingAdvice (日志记录)");
        System.out.println();

        aopProxy.addInterceptor(new SecurityAdvice());
        aopProxy.addInterceptor(new TransactionAdvice());
        aopProxy.addInterceptor(new LoggingAdvice());

        // 不调用 sortInterceptors()，按添加顺序执行
        UserService proxy = (UserService) aopProxy.getProxy();

        System.out.println("执行方法：");
        proxy.save("Alice");
    }

    /**
     * 场景 2: 使用 Ordered，按 Order 值排序执行
     */
    static void testWithOrdered(UserService target) throws Exception {
        JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(target);

        System.out.println("添加拦截器顺序（故意打乱）：");
        System.out.println("  1. TransactionAdvice (order=20)");
        System.out.println("  2. LoggingAdvice (order=30)");
        System.out.println("  3. SecurityAdvice (order=10, PriorityOrdered)");
        System.out.println();

        aopProxy.addInterceptor(new OrderedTransactionAdvice());
        aopProxy.addInterceptor(new OrderedLoggingAdvice());
        aopProxy.addInterceptor(new OrderedSecurityAdvice());

        // 调用 sortInterceptors() 进行排序
        System.out.println("调用 sortInterceptors() 排序后：");
        aopProxy.sortInterceptors();
        System.out.println();

        UserService proxy = (UserService) aopProxy.getProxy();

        System.out.println("执行方法：");
        proxy.save("Alice");
    }

    // ========== 目标类 ==========
    public interface UserService {
        void save(String name);
    }

    public static class UserServiceImpl implements UserService {
        @Override
        public void save(String name) {
            System.out.println("      [目标方法] 保存用户: " + name);
        }
    }

    // ========== 场景 1: 不使用 Ordered 的拦截器 ==========

    static class SecurityAdvice implements MethodInterceptor {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("  [SecurityAdvice] 安全检查");
            return invocation.proceed();
        }
    }

    static class TransactionAdvice implements MethodInterceptor {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("    [TransactionAdvice] 开启事务");
            Object result = invocation.proceed();
            System.out.println("    [TransactionAdvice] 提交事务");
            return result;
        }
    }

    static class LoggingAdvice implements MethodInterceptor {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("      [LoggingAdvice] 记录日志");
            return invocation.proceed();
        }
    }

    // ========== 场景 2: 使用 Ordered 的拦截器 ==========

    /**
     * 安全检查拦截器
     * 使用 PriorityOrdered，优先级最高
     * Order = 10
     */
    static class OrderedSecurityAdvice implements MethodInterceptor, PriorityOrdered {
        @Override
        public int getOrder() {
            return 10;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("  [SecurityAdvice] 安全检查 (PriorityOrdered, order=10)");
            return invocation.proceed();
        }
    }

    /**
     * 事务管理拦截器
     * 使用 Ordered
     * Order = 20
     */
    static class OrderedTransactionAdvice implements MethodInterceptor, Ordered {
        @Override
        public int getOrder() {
            return 20;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("    [TransactionAdvice] 开启事务 (Ordered, order=20)");
            Object result = invocation.proceed();
            System.out.println("    [TransactionAdvice] 提交事务");
            return result;
        }
    }

    /**
     * 日志记录拦截器
     * 使用 Ordered
     * Order = 30
     */
    static class OrderedLoggingAdvice implements MethodInterceptor, Ordered {
        @Override
        public int getOrder() {
            return 30;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("      [LoggingAdvice] 记录日志 (Ordered, order=30)");
            return invocation.proceed();
        }
    }
}
