package com.lx.governance.aichat.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
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

    @Value("${ai-chat.agent.base-url:http://localhost:8200}")
    private String doctorAssistantAgentBaseUrl;

    @Value("${ai-chat.agent.timeout-ms:8000}")
    private int doctorAssistantAgentTimeoutMs;

    @Value("${spring.ai.openai.connect-timeout:5s}")
    private Duration openAiConnectTimeout;

    @Value("${spring.ai.openai.read-timeout:60s}")
    private Duration openAiReadTimeout;

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

    @Bean
    public RestClient doctorAssistantAgentRestClient() {
        return RestClient.builder()
                .baseUrl(doctorAssistantAgentBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(clientHttpRequestFactory(doctorAssistantAgentTimeoutMs))
                .build();
    }

    /**
     * Customize the prototype RestClient.Builder used by Spring AI's OpenAI client.
     */
    @Bean
    public RestClientCustomizer openAiRestClientCustomizer() {
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(openAiConnectTimeout)
                        .withReadTimeout(openAiReadTimeout)));
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
