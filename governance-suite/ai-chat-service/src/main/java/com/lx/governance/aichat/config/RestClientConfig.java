package com.lx.governance.aichat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClient configuration for AI API integration
 */
@Configuration
public class RestClientConfig {

    @Value("${dify.api.base-url}")
    private String difyBaseUrl;

    @Value("${dify.api.key}")
    private String difyApiKey;

    @Value("${dify.api.timeout:30000}")
    private int difyTimeout;

    @Value("${deepseek.api.base-url:https://api.deepseek.com}")
    private String deepSeekBaseUrl;

    @Value("${deepseek.api.key}")
    private String deepSeekApiKey;

    @Value("${deepseek.api.timeout:60000}")
    private int deepSeekTimeout;

    /**
     * Create RestClient bean for Dify API calls
     */
    @Bean
    public RestClient difyRestClient() {
        return RestClient.builder()
                .baseUrl(difyBaseUrl)
                .defaultHeader("Authorization", "Bearer " + difyApiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(clientHttpRequestFactory(difyTimeout))
                .build();
    }

    /**
     * Create RestClient bean for DeepSeek API calls
     */
    @Bean
    public RestClient deepSeekRestClient() {
        return RestClient.builder()
                .baseUrl(deepSeekBaseUrl)
                .defaultHeader("Authorization", "Bearer " + deepSeekApiKey)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(clientHttpRequestFactory(deepSeekTimeout))
                .build();
    }

    /**
     * Create HTTP request factory with timeout configuration
     */
    private ClientHttpRequestFactory clientHttpRequestFactory(int timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
