package com.lx.governance.common.cloud;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.client.serviceregistry.AbstractAutoServiceRegistration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.nacos.retry-registration", name = "enabled", havingValue = "true")
public class NacosRegistrationRetryRunner {
    private static final Logger log = LoggerFactory.getLogger(NacosRegistrationRetryRunner.class);

    private final ObjectProvider<AbstractAutoServiceRegistration<?>> autoServiceRegistrationProvider;
    private final int maxAttempts;
    private final long retryIntervalMs;

    public NacosRegistrationRetryRunner(
            ObjectProvider<AbstractAutoServiceRegistration<?>> autoServiceRegistrationProvider,
            @Value("${app.nacos.retry-registration.max-attempts:30}") int maxAttempts,
            @Value("${app.nacos.retry-registration.retry-interval-ms:2000}") long retryIntervalMs) {
        this.autoServiceRegistrationProvider = autoServiceRegistrationProvider;
        this.maxAttempts = maxAttempts;
        this.retryIntervalMs = retryIntervalMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAfterReady() {
        AbstractAutoServiceRegistration<?> autoServiceRegistration = autoServiceRegistrationProvider.getIfAvailable();
        if (autoServiceRegistration == null) {
            return;
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                autoServiceRegistration.start();
                log.info("service registration succeeded on attempt {}", attempt);
                return;
            } catch (Exception ex) {
                if (attempt == maxAttempts) {
                    throw new IllegalStateException("service registration failed after " + maxAttempts + " attempts",
                            ex);
                }
                log.warn("service registration attempt {} failed, retrying in {} ms", attempt, retryIntervalMs, ex);
                sleepQuietly();
            }
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(retryIntervalMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("service registration retry interrupted", ex);
        }
    }
}
