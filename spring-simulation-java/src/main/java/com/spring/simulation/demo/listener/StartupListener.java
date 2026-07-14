package com.spring.simulation.demo.listener;

import com.spring.simulation.event.ApplicationListener;
import com.spring.simulation.event.ContextRefreshedEvent;

public class StartupListener implements ApplicationListener<ContextRefreshedEvent> {
    @Override
    public Class<ContextRefreshedEvent> getSupportedEventType() {
        return ContextRefreshedEvent.class;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        System.out.println("   [Listener] Application context refreshed at: " + event.getTimestamp());
        System.out.println("   [Listener] Application is ready to serve requests");
    }
}
