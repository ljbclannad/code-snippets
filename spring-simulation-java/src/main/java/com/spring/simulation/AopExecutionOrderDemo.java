package com.spring.simulation;

import com.spring.simulation.aop.*;

import java.lang.reflect.Method;

/**
 * AOP 拦截器执行顺序演示
 *
 * 演示为什么 Around 会打印 2 次，而 Before 和 After 只打印 1 次
 */
public class AopExecutionOrderDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("========== AOP 拦截器执行顺序演示 ==========\n");

        // 创建目标对象
        IUserService target = new UserServiceImpl();

        // 创建 AOP 代理
        JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(target);

        // 按顺序添加拦截器
        System.out.println("添加拦截器顺序：");
        System.out.println("  1. BeforeAdvice (只在 proceed 之前执行)");
        System.out.println("  2. AroundAdvice (在 proceed 前后都执行)");
        System.out.println("  3. AfterAdvice (只在 proceed 之后执行)");
        System.out.println();

        aopProxy.addInterceptor(new DetailedBeforeAdvice());
        aopProxy.addInterceptor(new DetailedAroundAdvice());
        aopProxy.addInterceptor(new DetailedAfterAdvice());

        // 获取代理对象
        IUserService proxy = (IUserService) aopProxy.getProxy();

        // 调用方法
        System.out.println("========== 开始调用 proxy.save(\"Alice\") ==========\n");
        proxy.save("Alice");
        System.out.println("\n========== 调用完成 ==========");
    }

    // ========== 目标类 ==========
    public interface IUserService {
        void save(String name);
    }

    public static class UserServiceImpl implements IUserService {
        @Override
        public void save(String name) {
            System.out.println("        │   │   │   [目标方法] 保存用户: " + name);
        }
    }

    // ========== 详细的拦截器实现 ==========

    /**
     * Before 拦截器：只在 proceed() 之前执行
     */
    static class DetailedBeforeAdvice implements MethodInterceptor {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("    ┌─→ [BeforeAdvice] 进入");
            System.out.println("    │   [BeforeAdvice] 执行 before 逻辑");
            System.out.println("    │   [BeforeAdvice] 调用 invocation.proceed()...");

            Object result = invocation.proceed();  // 阻塞，等待后续拦截器和目标方法执行完

            System.out.println("    │   [BeforeAdvice] proceed() 返回");
            System.out.println("    └─→ [BeforeAdvice] 退出（没有 after 逻辑）");
            return result;
        }
    }

    /**
     * Around 拦截器：在 proceed() 前后都执行
     */
    static class DetailedAroundAdvice implements MethodInterceptor {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("        ┌─→ [AroundAdvice] 进入");
            System.out.println("        │   [AroundAdvice] 执行 before 逻辑 ← 第 1 次打印");
            System.out.println("        │   [AroundAdvice] 调用 invocation.proceed()...");

            Object result = invocation.proceed();  // 阻塞，等待后续拦截器和目标方法执行完

            System.out.println("        │   [AroundAdvice] proceed() 返回");
            System.out.println("        │   [AroundAdvice] 执行 after 逻辑 ← 第 2 次打印");
            System.out.println("        └─→ [AroundAdvice] 退出");
            return result;
        }
    }

    /**
     * After 拦截器：只在 proceed() 之后执行
     */
    static class DetailedAfterAdvice implements MethodInterceptor {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
            System.out.println("            ┌─→ [AfterAdvice] 进入");
            System.out.println("            │   [AfterAdvice] 调用 invocation.proceed()...");

            try {
                Object result = invocation.proceed();  // 阻塞，等待目标方法执行完

                System.out.println("            │   [AfterAdvice] proceed() 返回");
                return result;
            } finally {
                System.out.println("            │   [AfterAdvice] 执行 after 逻辑（finally 块）");
                System.out.println("            └─→ [AfterAdvice] 退出");
            }
        }
    }
}
