package com.spring.simulation.transaction;

import com.spring.simulation.aop.MethodInterceptor;
import com.spring.simulation.aop.MethodInvocation;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * 事务拦截器：Spring 声明式事务的核心实现
 *
 * 通过共享 TransactionManager 执行事务传播；具体资源由事务管理器负责
 */
public class TransactionInterceptor implements MethodInterceptor {
    private final TransactionManager transactionManager;

    public TransactionInterceptor() { this(new TransactionManager()); }
    public TransactionInterceptor(TransactionManager transactionManager) {
        this.transactionManager = Objects.requireNonNull(transactionManager);
    }

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

            Object result;
            try {
                result = invocation.proceed();
            } catch (Throwable businessFailure) {
                boolean rollback = shouldRollback(tx, businessFailure);
                try {
                    if (rollback) transactionManager.rollback(status);
                    else transactionManager.commit(status);
                } catch (Throwable completionFailure) {
                    // 完成失败优先暴露，同时保留原始业务异常，避免丢失诊断信息。
                    if (completionFailure != businessFailure) completionFailure.addSuppressed(businessFailure);
                    throw completionFailure;
                }
                System.out.println("[Transaction] " + (rollback ? "回滚" : "按规则提交") + ": " + method.getName());
                throw businessFailure;
            }
            // 提交失败不属于业务异常，不能再次按业务回滚规则处理。
            transactionManager.commit(status);
            System.out.println("[Transaction] Completed transaction for: " + method.getName());
            return result;
        } else {
            // 方法没有 @Transactional 注解，直接执行
            return invocation.proceed();
        }
    }

    /** 最具体的异常类型优先；相同深度时 rollbackFor 优先；不遍历 cause 链。 */
    private boolean shouldRollback(Transactional tx, Throwable failure) {
        int closest = Integer.MAX_VALUE;
        boolean rollback = failure instanceof RuntimeException || failure instanceof Error;
        for (Class<? extends Throwable> rule : tx.rollbackFor()) {
            int depth = inheritanceDepth(failure.getClass(), rule);
            if (depth < closest) { closest = depth; rollback = true; }
        }
        for (Class<? extends Throwable> rule : tx.noRollbackFor()) {
            int depth = inheritanceDepth(failure.getClass(), rule);
            if (depth < closest) { closest = depth; rollback = false; }
        }
        return rollback;
    }

    private int inheritanceDepth(Class<?> actual, Class<? extends Throwable> rule) {
        int depth = 0;
        for (Class<?> type = actual; type != null; type = type.getSuperclass(), depth++) {
            if (type == rule) return depth;
        }
        return Integer.MAX_VALUE;
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
