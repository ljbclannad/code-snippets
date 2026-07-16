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
        Transactional tx = findTransactionalAnnotation(method, invocation.getTarget().getClass());
        if (tx != null) {
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

    /**
     * JDK 代理传入的是接口方法，事务注解通常标在实现类方法或实现类上，
     * 因此需要回到目标类解析，并让方法级注解覆盖类级注解。
     */
    private Transactional findTransactionalAnnotation(Method invokedMethod, Class<?> targetClass) {
        try {
            Method targetMethod = targetClass.getMethod(invokedMethod.getName(), invokedMethod.getParameterTypes());
            if (targetMethod.isAnnotationPresent(Transactional.class)) {
                return targetMethod.getAnnotation(Transactional.class);
            }
        } catch (NoSuchMethodException ignored) {
            // 目标类未公开该方法时，继续尝试接口方法与类级注解。
        }
        if (invokedMethod.isAnnotationPresent(Transactional.class)) {
            return invokedMethod.getAnnotation(Transactional.class);
        }
        return targetClass.getAnnotation(Transactional.class);
    }
}
