package com.spring.simulation.event;

public interface ApplicationListener<E extends ApplicationEvent> {
    void onApplicationEvent(E event);
}
