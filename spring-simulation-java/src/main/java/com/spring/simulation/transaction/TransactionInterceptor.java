package com.spring.simulation.transaction;

import com.spring.simulation.aop.MethodInterceptor;
import com.spring.simulation.aop.MethodInvocation;
import java.lang.reflect.Method;

/**
 * 事务拦截器：Spring 声明式事务的核心实现
 *
 * 支持完整的事务传播机制
 */
public class TransactionInterceptor implements MethodInterceptor {
    private TransactionManager transactionManager = new TransactionManager();

    @Override
    public Object invoke(Object proxy, Method method, Object[] args, MethodInvocation invocation) throws Throwable {
        if (method.isAnnotationPresent(Transactional.class)) {
            Transactional tx = method.getAnnotation(Transactional.class);
            Propagation propagation = Propagation.valueOf(tx.propagation());

            System.out.println("[Transaction] Method: " + method.getName());
            System.out.println("   - Propagation: " + tx.propagation());
            System.out.println("   - Isolation: " + tx.isolation());
            System.out.println("   - ReadOnly: " + tx.readOnly());

            // 根据传播行为开始事务
            TransactionManager.TransactionStatus status = transactionManager.begin(propagation);

            try {
                // 执行目标方法
                Object result = invocation.proceed();

                // 提交事务
                transactionManager.commit(status);
                System.out.println("[Transaction] Completed transaction for: " + method.getName());
                return result;
            } catch (Throwable e) {
                // 回滚事务
                transactionManager.rollback(status);
                System.out.println("[Transaction] Rolled back transaction for: " + method.getName());
                throw e;
            }
        } else {
            // 方法没有 @Transactional 注解，直接执行
            return invocation.proceed();
        }
    }
}
