package com.lx.governance.nacossimulator.service;

import com.lx.governance.nacossimulator.dto.AdminStateResponse;
import com.lx.governance.nacossimulator.dto.PendingLongPollView;
import com.lx.governance.nacossimulator.model.ConfigItem;
import com.lx.governance.nacossimulator.model.ServiceInstanceRecord;
import com.lx.governance.nacossimulator.store.InMemoryConfigStore;
import com.lx.governance.nacossimulator.store.InMemoryLongPollStore;
import com.lx.governance.nacossimulator.store.InMemoryRegistryStore;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AdminStateService {

    private final InMemoryConfigStore configStore;
    private final InMemoryRegistryStore registryStore;
    private final InMemoryLongPollStore longPollStore;

    public AdminStateService(InMemoryConfigStore configStore,
            InMemoryRegistryStore registryStore,
            InMemoryLongPollStore longPollStore) {
        this.configStore = configStore;
        this.registryStore = registryStore;
        this.longPollStore = longPollStore;
    }

    public AdminStateResponse getState() {
        int healthyInstances = registryStore.listAll().size();
        return new AdminStateResponse(
                registryStore.serviceCount(),
                healthyInstances,
                longPollStore.size());
    }

    public List<ConfigItem> listConfigs() {
        return configStore.listAll();
    }

    public List<ServiceInstanceRecord> listInstances(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return registryStore.listAll();
        }
        return registryStore.list(serviceName);
    }

    public List<PendingLongPollView> listLongPolls() {
        return longPollStore.list().stream()
                .map(poll -> new PendingLongPollView(poll.getClientId(), poll.getWatchKeys()))
                .toList();
    }
}
