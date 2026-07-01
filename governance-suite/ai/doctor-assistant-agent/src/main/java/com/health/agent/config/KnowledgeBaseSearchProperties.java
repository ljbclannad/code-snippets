package com.health.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "doctor.agent.knowledge-base")
public class KnowledgeBaseSearchProperties {

    private boolean enabled = true;

    private String baseUrl = "http://localhost:8103";

    private String kbId = "medical-default";

    private int topK = 3;

    private double similarityThreshold = 0.2d;

    private boolean useRewrite = true;

    private boolean rerank = true;

    private boolean hybridSearch = true;

    private String businessDomain = "medical";

    private String applicableScope = "doctor_assistant";

    private String trustLevel = "HIGH";

    private int connectTimeoutSeconds = 5;

    private int readTimeoutSeconds = 10;
}
