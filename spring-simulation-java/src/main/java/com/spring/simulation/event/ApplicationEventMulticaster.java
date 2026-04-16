package com.spring.simulation.event;

import java.util.ArrayList;
import java.util.List;

public class ApplicationEventMulticaster {
    private List<ApplicationListener> listeners = new ArrayList<>();

    public void addApplicationListener(ApplicationListener<?> listener) {
        listeners.add(listener);
        System.out.println("[Event] Registered listener: " + listener.getClass().getSimpleName());
    }

    public void multicastEvent(ApplicationEvent event) {
        System.out.println("\n[Event] Publishing event: " + event.getClass().getSimpleName());
        for (ApplicationListener listener : listeners) {
            listener.onApplicationEvent(event);
        }
    }
}
