package com.lx.governance.nacossimulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lx.governance.nacossimulator.dto.ConfigProbe;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LongPollingServiceTest {

    @Test
    void returnsImmediatelyWhenClientMd5IsStale() {
        ConfigService configService = new ConfigService();
        LongPollingService longPollingService = new LongPollingService(configService, 5_000L);
        configService.setLongPollingService(longPollingService);
        configService.publish("common.yml", "DEFAULT_GROUP", "value-2");

        var response = longPollingService.listen("client-a",
                List.of(new ConfigProbe("common.yml", "DEFAULT_GROUP", "old-md5")));

        assertThat(response.changed()).isTrue();
        assertThat(response.items()).hasSize(1);
    }

    @Test
    void waitingRequestCompletesAfterPublish() throws Exception {
        ConfigService configService = new ConfigService();
        LongPollingService longPollingService = new LongPollingService(configService, 5_000L);
        configService.setLongPollingService(longPollingService);
        configService.publish("common.yml", "DEFAULT_GROUP", "value-1");
        String currentMd5 = configService.get("common.yml", "DEFAULT_GROUP").orElseThrow().getMd5();

        var future = longPollingService.listenAsync("client-a",
                List.of(new ConfigProbe("common.yml", "DEFAULT_GROUP", currentMd5)));

        configService.publish("common.yml", "DEFAULT_GROUP", "value-2");

        var response = future.get(2, TimeUnit.SECONDS);
        assertThat(response.changed()).isTrue();
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).getContent()).isEqualTo("value-2");
    }
}
