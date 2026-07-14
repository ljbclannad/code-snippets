package com.spring.simulation.event;

public interface ApplicationListener<E extends ApplicationEvent> {
    /**
     * 声明监听器能够处理的事件类型，供事件多播器在分发前进行类型校验。
     */
    Class<E> getSupportedEventType();

    void onApplicationEvent(E event);
}
