package com.lx.governance.nacossimulator.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConfigServiceTest {

    @Test
    void publishCreatesVersionOneAndMd5() {
        ConfigService service = new ConfigService();

        var item = service.publish("common.yml", "DEFAULT_GROUP", "gateway.rate=5");

        assertThat(item.getVersion()).isEqualTo(1L);
        assertThat(item.getMd5()).isNotBlank();
        assertThat(item.getContent()).isEqualTo("gateway.rate=5");
    }

    @Test
    void publishSameKeyTwiceIncrementsVersion() {
        ConfigService service = new ConfigService();

        service.publish("common.yml", "DEFAULT_GROUP", "v1");
        var second = service.publish("common.yml", "DEFAULT_GROUP", "v2");

        assertThat(second.getVersion()).isEqualTo(2L);
        assertThat(second.getContent()).isEqualTo("v2");
    }
}
