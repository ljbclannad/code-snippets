package com.spring.simulation.event;

import java.util.ArrayList;
import java.util.List;

public class ApplicationEventMulticaster {
    private final List<ApplicationListener<? extends ApplicationEvent>> listeners = new ArrayList<>();

    public void addApplicationListener(ApplicationListener<? extends ApplicationEvent> listener) {
        listeners.add(listener);
        System.out.println("[Event] Registered listener: " + listener.getClass().getSimpleName());
    }

    public void multicastEvent(ApplicationEvent event) {
        System.out.println("\n[Event] Publishing event: " + event.getClass().getSimpleName());
        for (ApplicationListener<? extends ApplicationEvent> listener : listeners) {
            notifyListener(listener, event);
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
