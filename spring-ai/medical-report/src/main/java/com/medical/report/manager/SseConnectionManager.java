package com.medical.report.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class SseConnectionManager {

    private final Map<String, SseEmitter> connections = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final long DEFAULT_TIMEOUT = 300000L; // 5 minutes
    private static final int MAX_CONNECTIONS = 100;

    public SseEmitter createConnection(String connectionId) {
        if (connectionCount.get() >= MAX_CONNECTIONS) {
            log.warn("Max connections reached: {}", MAX_CONNECTIONS);
            throw new IllegalStateException("Maximum concurrent connections reached");
        }

        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        connections.put(connectionId, emitter);
        connectionCount.incrementAndGet();

        emitter.onCompletion(() -> {
            removeConnection(connectionId);
            log.debug("SSE connection completed: {}", connectionId);
        });

        emitter.onTimeout(() -> {
            removeConnection(connectionId);
            log.warn("SSE connection timeout: {}", connectionId);
        });

        emitter.onError(e -> {
            removeConnection(connectionId);
            log.error("SSE connection error: {}", connectionId, e);
        });

        log.info("SSE connection created: {} (total: {})", connectionId, connectionCount.get());
        return emitter;
    }

    public void sendEvent(String connectionId, String eventName, Object data) throws IOException {
        SseEmitter emitter = connections.get(connectionId);
        if (emitter != null) {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } else {
            log.warn("Connection not found: {}", connectionId);
        }
    }

    public void completeConnection(String connectionId) {
        SseEmitter emitter = connections.get(connectionId);
        if (emitter != null) {
            emitter.complete();
            removeConnection(connectionId);
        }
    }

    public void completeWithError(String connectionId, Throwable error) {
        SseEmitter emitter = connections.get(connectionId);
        if (emitter != null) {
            emitter.completeWithError(error);
            removeConnection(connectionId);
        }
    }

    private void removeConnection(String connectionId) {
        if (connections.remove(connectionId) != null) {
            connectionCount.decrementAndGet();
            log.debug("SSE connection removed: {} (remaining: {})", connectionId, connectionCount.get());
        }
    }

    public int getActiveConnections() {
        return connectionCount.get();
    }

    public void shutdown() {
        log.info("Shutting down SSE connections: {}", connectionCount.get());
        connections.forEach((id, emitter) -> {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.error("Error closing connection: {}", id, e);
            }
        });
        connections.clear();
        connectionCount.set(0);
    }
}
