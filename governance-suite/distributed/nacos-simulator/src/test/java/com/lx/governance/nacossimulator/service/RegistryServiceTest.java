package com.lx.governance.nacossimulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistryServiceTest {

    @Test
    void registerAddsHealthyInstance() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);
        RegistryService service = new RegistryService(clock, 15_000L);

        var instance = service.register("order-service", "127.0.0.1", 8081, Map.of("zone", "A"), 15_000L);

        assertThat(instance.isHealthy()).isTrue();
        assertThat(service.listHealthyInstances("order-service")).hasSize(1);
    }

    @Test
    void expireRemovesInstanceAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);
        RegistryService service = new RegistryService(clock, 15_000L);
        var instance = service.register("order-service", "127.0.0.1", 8081, Map.of(), 1_000L);

        clock.advanceSeconds(2);
        service.expireStaleInstances();

        assertThat(service.listHealthyInstances("order-service")).isEmpty();
        assertThat(service.findInstance("order-service", instance.getInstanceId())).isEmpty();
    }

    @Test
    void heartbeatKeepsInstanceAliveUntilNextExpirationWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);
        RegistryService service = new RegistryService(clock, 15_000L);
        var instance = service.register("order-service", "127.0.0.1", 8081, Map.of(), 1_000L);

        clock.advanceMillis(900);
        service.heartbeat("order-service", instance.getInstanceId());
        clock.advanceMillis(900);
        service.expireStaleInstances();

        assertThat(service.listHealthyInstances("order-service")).hasSize(1);
    }
}
