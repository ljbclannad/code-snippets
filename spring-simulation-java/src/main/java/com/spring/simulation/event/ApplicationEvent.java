package com.spring.simulation.event;

public class ApplicationEvent {
    private Object source;
    private long timestamp;

    public ApplicationEvent(Object source) {
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }

    public Object getSource() {
        return source;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
