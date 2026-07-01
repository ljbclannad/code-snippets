package com.lx.governance.nacossimulator.service;

import com.lx.governance.nacossimulator.model.ServiceInstanceRecord;
import com.lx.governance.nacossimulator.store.InMemoryRegistryStore;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RegistryService {

    private final InMemoryRegistryStore store;
    private final Clock clock;
    private final long defaultTtlMillis;

    @Autowired
    public RegistryService(InMemoryRegistryStore store,
            @Value("${simulator.registry.default-ttl-ms:15000}") long defaultTtlMillis) {
        this(store, Clock.systemUTC(), defaultTtlMillis);
    }

    public RegistryService(Clock clock, long defaultTtlMillis) {
        this(new InMemoryRegistryStore(), clock, defaultTtlMillis);
    }

    public RegistryService(InMemoryRegistryStore store, Clock clock, long defaultTtlMillis) {
        this.store = store;
        this.clock = clock;
        this.defaultTtlMillis = defaultTtlMillis;
    }

    public ServiceInstanceRecord register(String serviceName, String ip, int port,
            Map<String, String> metadata, Long ttlMillis) {
        Instant now = clock.instant();
        ServiceInstanceRecord record = ServiceInstanceRecord.builder()
                .serviceName(serviceName)
                .instanceId(instanceId(serviceName, ip, port))
                .ip(ip)
                .port(port)
                .metadata(metadata)
                .registeredAt(now)
                .lastHeartbeatAt(now)
                .ttlMillis(ttlMillis != null ? ttlMillis : defaultTtlMillis)
                .healthy(true)
                .build();
        return store.put(record);
    }

    public ServiceInstanceRecord heartbeat(String serviceName, String instanceId) {
        ServiceInstanceRecord existing = store.find(serviceName, instanceId)
                .orElseThrow(() -> new IllegalArgumentException("instance not found"));
        ServiceInstanceRecord updated = existing.toBuilder()
                .lastHeartbeatAt(clock.instant())
                .healthy(true)
                .build();
        return store.put(updated);
    }

    public void deregister(String serviceName, String instanceId) {
        ServiceInstanceRecord existing = store.find(serviceName, instanceId)
                .orElseThrow(() -> new IllegalArgumentException("instance not found"));
        store.remove(existing.getServiceName(), existing.getInstanceId());
    }

    public List<ServiceInstanceRecord> listHealthyInstances(String serviceName) {
        return store.list(serviceName).stream()
                .filter(ServiceInstanceRecord::isHealthy)
                .toList();
    }

    public int expireStaleInstances() {
        int removed = 0;
        for (ServiceInstanceRecord record : store.listAll()) {
            long elapsed = clock.instant().toEpochMilli() - record.getLastHeartbeatAt().toEpochMilli();
            if (elapsed > record.getTtlMillis()) {
                store.remove(record.getServiceName(), record.getInstanceId());
                removed++;
            }
        }
        return removed;
    }

    public Optional<ServiceInstanceRecord> findInstance(String serviceName, String instanceId) {
        return store.find(serviceName, instanceId);
    }

    private String instanceId(String serviceName, String ip, int port) {
        return serviceName + "#" + ip + "#" + port;
    }
}
