package com.spring.simulation.event;

import java.util.ArrayList;
import java.util.List;

import com.spring.simulation.ioc.factory.BeanFactory;

public class ApplicationEventMulticaster {
    private final List<ApplicationListener<? extends ApplicationEvent>> listeners = new ArrayList<>();
    private final List<String> listenerBeanNames = new ArrayList<>();
    private BeanFactory beanFactory;

    /**
     * 设置用于延迟获取监听器 Bean 的 BeanFactory。
     *
     * <p>这与 Spring 的 addApplicationListenerBean 思路一致：阶段 10 只登记 Bean 名称，
     * 不会为了注册监听器而提前实例化普通单例 Bean。</p>
     */
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    public void addApplicationListener(ApplicationListener<? extends ApplicationEvent> listener) {
        listeners.add(listener);
        System.out.println("[Event] Registered listener: " + listener.getClass().getSimpleName());
    }

    public void addApplicationListenerBean(String listenerBeanName) {
        if (beanFactory == null) {
            throw new IllegalStateException("BeanFactory must be set before registering listener beans");
        }
        listenerBeanNames.add(listenerBeanName);
        System.out.println("[Event] Registered listener bean: " + listenerBeanName + " (lazy)");
    }

    public void multicastEvent(ApplicationEvent event) {
        System.out.println("\n[Event] Publishing event: " + event.getClass().getSimpleName());
        for (ApplicationListener<? extends ApplicationEvent> listener : listeners) {
            notifyListener(listener, event);
        }
        for (String listenerBeanName : listenerBeanNames) {
            notifyListenerBean(listenerBeanName, event);
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyListenerBean(String listenerBeanName, ApplicationEvent event) {
        try {
            Object bean = beanFactory.getBean(listenerBeanName);
            if (!(bean instanceof ApplicationListener)) {
                throw new IllegalStateException("Bean '" + listenerBeanName
                        + "' does not implement ApplicationListener");
            }
            notifyListener((ApplicationListener<ApplicationEvent>) bean, event);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to notify listener bean '" + listenerBeanName + "'", ex);
        }
    }

    /**
     * 先按监听器声明的事件类型判断，再通过 Class.cast() 完成运行时安全转换。
     */
    private <E extends ApplicationEvent> void notifyListener(
            ApplicationListener<E> listener, ApplicationEvent event) {
        Class<E> supportedEventType = listener.getSupportedEventType();
        if (supportedEventType.isInstance(event)) {
            listener.onApplicationEvent(supportedEventType.cast(event));
        }
    }
}
