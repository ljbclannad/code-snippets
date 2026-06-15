package com.lx.governance.vectormemory.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.lx.governance.vectormemory.config.MemoryRetrievalProperties;
import com.lx.governance.vectormemory.dto.MemoryItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 通过 HTTP 调用外部 BGE Rerank 服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BgeRerankClient {

    private final MemoryRetrievalProperties properties;
    private final RestTemplateBuilder restTemplateBuilder;

    public List<MemoryItemDTO> rerank(String query, List<MemoryItemDTO> memories, int topK) {
        if (memories == null || memories.isEmpty()) {
            return List.of();
        }

        MemoryRetrievalProperties.RemoteConfig remoteConfig = properties.getRerank().getRemote();
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(30))
                .build();

        List<String> documents = memories.stream()
                .map(MemoryItemDTO::getContent)
                .toList();

        BgeRerankRequest request = new BgeRerankRequest(remoteConfig.getModel(), query, documents);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String endpoint = normalizeEndpoint(remoteConfig.getBaseUrl(), remoteConfig.getPath());
        log.info("Calling external BGE rerank service: endpoint={}, candidateCount={}", endpoint, memories.size());

        BgeRerankResponse response = restTemplate.postForObject(
                endpoint,
                new HttpEntity<>(request, headers),
                BgeRerankResponse.class);

        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new IllegalStateException("Empty rerank response from external service");
        }

        List<MemoryItemDTO> rescored = new ArrayList<>(memories);
        for (BgeRerankResult result : response.results()) {
            if (result.index() == null || result.index() < 0 || result.index() >= rescored.size()) {
                log.warn("Ignoring invalid rerank result index: {}", result.index());
                continue;
            }
            rescored.get(result.index()).setSimilarity(result.relevanceScore());
        }

        return rescored.stream()
                .sorted(Comparator.comparing(MemoryItemDTO::getSimilarity,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(topK)
                .toList();
    }

    private String normalizeEndpoint(String baseUrl, String path) {
        String safeBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String safePath = path.startsWith("/") ? path : "/" + path;
        return safeBaseUrl + safePath;
    }

    private record BgeRerankRequest(String model, String query, List<String> documents) {
    }

    private record BgeRerankResponse(String id, List<BgeRerankResult> results) {
    }

    private record BgeRerankResult(
            Integer index,
            @JsonProperty("relevance_score") Double relevanceScore,
            String document) {
    }
}
