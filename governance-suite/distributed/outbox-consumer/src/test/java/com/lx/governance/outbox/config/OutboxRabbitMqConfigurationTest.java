package com.lx.governance.outbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class OutboxRabbitMqConfigurationTest {

    @Test
    void enablesCorrelatedPublisherConfirmsOnSpringRabbitMqProperties() throws Exception {
        List<PropertySource<?>> propertySources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        assertThat(propertySources)
                .anySatisfy(propertySource -> assertThat(
                        propertySource.getProperty("spring.rabbitmq.publisher-confirm-type"))
                        .isEqualTo("correlated"));
    }
}
