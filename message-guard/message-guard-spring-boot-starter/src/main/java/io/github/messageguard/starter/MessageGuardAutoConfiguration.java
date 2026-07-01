package io.github.messageguard.starter;

import io.github.messageguard.core.engine.DefaultRetryDecisionEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Set;

@AutoConfiguration
public class MessageGuardAutoConfiguration {

    @Bean
    public DefaultRetryDecisionEngine defaultRetryDecisionEngine() {
        return new DefaultRetryDecisionEngine(Set.of(), Set.of());
    }
}
