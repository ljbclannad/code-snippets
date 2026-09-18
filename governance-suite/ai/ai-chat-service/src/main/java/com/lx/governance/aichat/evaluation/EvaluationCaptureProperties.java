package com.lx.governance.aichat.evaluation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai-chat.evaluation-capture")
public class EvaluationCaptureProperties {
    private boolean enabled;
    private String baseUrl = "http://127.0.0.1:8104";
    private String agentBaseUrl = "http://127.0.0.1:8088";
    private String hashKey = "";
    private int queueCapacity = 128;
    private int timeoutMs = 1500;
    private int maxAttempts = 2;
    private int maxPayloadBytes = 262144;
    private int maxTextLength = 16000;
}
