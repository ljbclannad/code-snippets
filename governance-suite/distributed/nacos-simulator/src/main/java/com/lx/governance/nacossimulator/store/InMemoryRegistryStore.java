package com.lx.governance.nacossimulator.store;

import com.lx.governance.nacossimulator.model.ServiceInstanceRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryRegistryStore {

    private final ConcurrentMap<String, ConcurrentMap<String, ServiceInstanceRecord>> services = new ConcurrentHashMap<>();

    public ServiceInstanceRecord put(ServiceInstanceRecord record) {
        services.computeIfAbsent(record.getServiceName(), ignored -> new ConcurrentHashMap<>())
                .put(record.getInstanceId(), record);
        return record;
    }

    public Optional<ServiceInstanceRecord> find(String serviceName, String instanceId) {
        ConcurrentMap<String, ServiceInstanceRecord> instances = services.get(serviceName);
        if (instances == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(instances.get(instanceId));
    }

    public void remove(String serviceName, String instanceId) {
        ConcurrentMap<String, ServiceInstanceRecord> instances = services.get(serviceName);
        if (instances != null) {
            instances.remove(instanceId);
        }
    }

    public List<ServiceInstanceRecord> list(String serviceName) {
        ConcurrentMap<String, ServiceInstanceRecord> instances = services.get(serviceName);
        if (instances == null) {
            return List.of();
        }
        return new ArrayList<>(instances.values());
    }

    public List<ServiceInstanceRecord> listAll() {
        List<ServiceInstanceRecord> all = new ArrayList<>();
        for (ConcurrentMap<String, ServiceInstanceRecord> instances : services.values()) {
            all.addAll(instances.values());
        }
        return all;
    }

    public int serviceCount() {
        return services.size();
    }
}
