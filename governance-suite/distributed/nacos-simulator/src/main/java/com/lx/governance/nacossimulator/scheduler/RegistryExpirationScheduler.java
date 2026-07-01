package com.lx.governance.nacossimulator.scheduler;

import com.lx.governance.nacossimulator.service.RegistryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RegistryExpirationScheduler {

    private final RegistryService registryService;

    public RegistryExpirationScheduler(RegistryService registryService) {
        this.registryService = registryService;
    }

    @Scheduled(fixedDelayString = "${simulator.registry.expiration-scan-interval-ms:3000}")
    public void expireStaleInstances() {
        registryService.expireStaleInstances();
    }
}
