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

    @Value("${ai-chat.agent.base-url:http://localhost:8088}")
    private String doctorAssistantAgentBaseUrl;

    @Value("${ai-chat.agent.timeout-ms:15000}")
    private int doctorAssistantAgentTimeoutMs;

    @Value("${spring.ai.openai.connect-timeout:5s}")
    private Duration openAiConnectTimeout;

    @Value("${spring.ai.openai.read-timeout:60s}")
    private Duration openAiReadTimeout;

    @Value("${ai-chat.kb.base-url:http://localhost:8103}")
    private String knowledgeBaseBaseUrl;

    @Value("${ai-chat.kb.timeout-ms:10000}")
    private int knowledgeBaseTimeoutMs;

    @Bean
    public RestClient doctorAssistantAgentRestClient() {
        return RestClient.builder()
                .baseUrl(doctorAssistantAgentBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(clientHttpRequestFactory(doctorAssistantAgentTimeoutMs))
                .build();
    }

    @Bean
    public RestClient knowledgeBaseRestClient() {
        return RestClient.builder()
                .baseUrl(knowledgeBaseBaseUrl)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(clientHttpRequestFactory(knowledgeBaseTimeoutMs))
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
