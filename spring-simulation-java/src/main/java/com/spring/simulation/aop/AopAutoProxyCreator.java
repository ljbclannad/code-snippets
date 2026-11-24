package com.spring.simulation.aop;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.spring.simulation.ioc.aware.BeanFactoryAware;
import com.spring.simulation.ioc.factory.BeanDefinition;
import com.spring.simulation.ioc.factory.BeanFactory;
import com.spring.simulation.ioc.factory.BeanFactoryPostProcessor;
import com.spring.simulation.ioc.factory.BeanPostProcessor;
import com.spring.simulation.transaction.TransactionInterceptor;
import com.spring.simulation.transaction.Transactional;

/**
 * 阶段 6 注册的自动代理创建器。
 *
 * 在每个业务 Bean 初始化完成后，统一识别 @Transactional 与 @Aspect 通知；
 * 命中任一规则时返回包含全部匹配拦截器的 JDK 代理，因此被代理 Bean 应通过其业务接口注入或获取。
 */
public class AopAutoProxyCreator implements com.spring.simulation.ioc.factory.SmartInstantiationAwareBeanPostProcessor, BeanFactoryAware {
    private BeanFactory beanFactory;
    private final Map<String, Object> earlyProxies = new HashMap<>();
    @Override
    public Object getEarlyBeanReference(Object bean, String beanName) throws Exception {
        Object proxy = wrapIfNecessary(bean, beanName);
        earlyProxies.put(beanName, proxy);
        return proxy;
    }
    @Override
    public void clearEarlyReference(String beanName) { earlyProxies.remove(beanName); }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws Exception {
        if (earlyProxies.containsKey(beanName)) return earlyProxies.remove(beanName);
        return wrapIfNecessary(bean, beanName);
    }

    private Object wrapIfNecessary(Object bean, String beanName) throws Exception {
        if (isInfrastructureBean(bean)) {
            return bean;
        }

        List<MethodInterceptor> interceptors = new ArrayList<>();
        if (hasTransactionalMethod(bean.getClass())) {
            interceptors.add(new TransactionInterceptor(beanFactory.getTransactionManager()));
        }
        interceptors.addAll(findMatchingAspectInterceptors(bean.getClass()));

        if (interceptors.isEmpty()) {
            return bean;
        }
        if (bean.getClass().getInterfaces().length == 0) {
            System.out.println("   [AOP] Skipping " + beanName
                    + ": JDK proxy requires the target bean to implement an interface");
            return bean;
        }

        JdkDynamicAopProxy proxy = new JdkDynamicAopProxy(bean);
        for (MethodInterceptor interceptor : interceptors) {
            proxy.addInterceptor(interceptor);
        }
        proxy.sortInterceptors();
        System.out.println("   [AOP] Auto-proxying bean: " + beanName);
        return proxy.getProxy();
    }

    private boolean isInfrastructureBean(Object bean) {
        return bean instanceof BeanPostProcessor
                || bean instanceof BeanFactoryPostProcessor
                || bean.getClass().isAnnotationPresent(Aspect.class);
    }

    private boolean hasTransactionalMethod(Class<?> targetClass) {
        if (targetClass.isAnnotationPresent(Transactional.class)) {
            return true;
        }
        for (Method method : targetClass.getMethods()) {
            if (method.isAnnotationPresent(Transactional.class)) {
                return true;
            }
        }
        for (Class<?> interfaceType : targetClass.getInterfaces()) {
            if (hasTransactionalDeclaration(interfaceType)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTransactionalDeclaration(Class<?> interfaceType) {
        if (interfaceType.isAnnotationPresent(Transactional.class)) {
            return true;
        }
        for (Method method : interfaceType.getMethods()) {
            if (method.isAnnotationPresent(Transactional.class)) {
                return true;
            }
        }
        for (Class<?> parentInterface : interfaceType.getInterfaces()) {
            if (hasTransactionalDeclaration(parentInterface)) {
                return true;
            }
        }
        return false;
    }

    private List<MethodInterceptor> findMatchingAspectInterceptors(Class<?> targetClass) throws Exception {
        List<MethodInterceptor> interceptors = new ArrayList<>();
        for (Map.Entry<String, BeanDefinition> entry : beanFactory.getBeanDefinitions().entrySet()) {
            if (!entry.getValue().getBeanClass().isAnnotationPresent(Aspect.class)) {
                continue;
            }
            Object aspect = beanFactory.getBean(entry.getKey());
            interceptors.addAll(createAspectInterceptors(aspect, targetClass));
        }
        return interceptors;
    }

    private List<MethodInterceptor> createAspectInterceptors(Object aspect, Class<?> targetClass) {
        Map<String, String> namedPointcuts = findNamedPointcuts(aspect.getClass());
        List<MethodInterceptor> interceptors = new ArrayList<>();
        for (Method method : aspect.getClass().getDeclaredMethods()) {
            AspectMethodInterceptor.AdviceType adviceType = findAdviceType(method);
            if (adviceType == null) {
                continue;
            }
            String expression = resolvePointcutExpression(method, namedPointcuts, adviceType);
            AspectMethodInterceptor interceptor = new AspectMethodInterceptor(
                    aspect, method, expression, adviceType);
            if (interceptor.matchesAnyMethod(targetClass)) {
                interceptors.add(interceptor);
            }
        }
        return interceptors;
    }

    private Map<String, String> findNamedPointcuts(Class<?> aspectClass) {
        Map<String, String> pointcuts = new HashMap<>();
        for (Method method : aspectClass.getDeclaredMethods()) {
            Pointcut pointcut = method.getAnnotation(Pointcut.class);
            if (pointcut != null) {
                pointcuts.put(method.getName() + "()", pointcut.value());
            }
        }
        return pointcuts;
    }

    private AspectMethodInterceptor.AdviceType findAdviceType(Method method) {
        if (method.isAnnotationPresent(Before.class)) {
            return AspectMethodInterceptor.AdviceType.BEFORE;
        }
        if (method.isAnnotationPresent(Around.class)) {
            return AspectMethodInterceptor.AdviceType.AROUND;
        }
        if (method.isAnnotationPresent(After.class)) {
            return AspectMethodInterceptor.AdviceType.AFTER;
        }
        return null;
    }

    private String resolvePointcutExpression(Method method, Map<String, String> namedPointcuts,
            AspectMethodInterceptor.AdviceType adviceType) {
        String expression;
        switch (adviceType) {
            case BEFORE:
                expression = method.getAnnotation(Before.class).value();
                break;
            case AROUND:
                expression = method.getAnnotation(Around.class).value();
                break;
            case AFTER:
                expression = method.getAnnotation(After.class).value();
                break;
            default:
                throw new IllegalStateException("Unsupported advice type: " + adviceType);
        }
        return namedPointcuts.getOrDefault(expression, expression);
    }
}
