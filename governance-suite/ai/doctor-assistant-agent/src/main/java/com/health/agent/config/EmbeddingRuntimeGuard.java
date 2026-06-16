package com.health.agent.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class EmbeddingRuntimeGuard {

    private final Environment environment;

    @Value("${embedding.use-mock:true}")
    private boolean useMockEmbedding;

    public EmbeddingRuntimeGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validateEmbeddingMode() {
        boolean productionProfile = Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile));
        if (productionProfile && useMockEmbedding) {
            throw new IllegalStateException("生产环境禁止使用 Mock Embedding，请关闭 embedding.use-mock");
        }
    }
}
