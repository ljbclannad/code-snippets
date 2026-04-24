package com.medical.report.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

@Configuration
@EnableRetry
public class ResilienceConfig {
    // Retry configuration is handled via @Retryable annotations in service classes
}
