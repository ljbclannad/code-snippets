package com.spring.simulation.ioc.scope;

import java.util.Map;
import java.util.function.Supplier;

/**
 * request 作用域：同一个请求内按 Bean 名称复用实例，请求结束后由上下文清理。
 */
public class RequestScope implements BeanScopeHandler {

    @Override
    public Object get(String beanName, Supplier<Object> objectFactory) {
        Map<String, Object> requestBeans = RequestScopeContextHolder.currentRequestBeans();
        return requestBeans.computeIfAbsent(beanName, ignored -> objectFactory.get());
    }
}
