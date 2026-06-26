package com.health.agent.client;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.health.agent.config.KnowledgeBaseSearchProperties;
import com.health.agent.dto.kb.KbApiResponse;
import com.health.agent.dto.kb.KbSearchRequest;
import com.health.agent.dto.kb.KbSearchResponse;
import com.health.agent.dto.kb.KbSearchResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final KnowledgeBaseSearchProperties properties;
    private final ObjectMapper objectMapper;

    public boolean isEnabled() {
        return properties.isEnabled()
                && properties.getBaseUrl() != null
                && !properties.getBaseUrl().isBlank()
                && properties.getKbId() != null
                && !properties.getKbId().isBlank();
    }

    public List<KbSearchResult> searchMedicalKnowledge(String query, int topK) throws IOException {
        return searchMedicalKnowledge(query, topK, null, null, null, null);
    }

    public List<KbSearchResult> searchMedicalKnowledge(
            String query,
            int topK,
            String retrievalProfile,
            String applicableScope,
            String trustLevel,
            List<String> sourceTypes) throws IOException {
        if (!isEnabled()) {
            return List.of();
        }

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(properties.getReadTimeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .build();

        KbSearchRequest request = new KbSearchRequest();
        request.setQuery(query);
        request.setTopK(Math.max(1, Math.min(topK, properties.getTopK())));
        request.setSimilarityThreshold(properties.getSimilarityThreshold());
        request.setUseRewrite(properties.isUseRewrite());
        request.setRerank(properties.isRerank());
        request.setHybridSearch(properties.isHybridSearch());
        request.setBusinessDomain(properties.getBusinessDomain());
        request.setRetrievalProfile(retrievalProfile);
        request.setApplicableScope(applicableScope != null && !applicableScope.isBlank()
                ? applicableScope
                : properties.getApplicableScope());
        request.setTrustLevel(trustLevel != null && !trustLevel.isBlank()
                ? trustLevel
                : properties.getTrustLevel());
        request.setSourceTypes(sourceTypes);

        String url = properties.getBaseUrl() + "/api/kb/" + properties.getKbId() + "/search";
        String body = objectMapper.writeValueAsString(request);
        Request httpRequest = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = client.newCall(httpRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("knowledge-base-service HTTP " + response.code());
            }
            String responseBody = response.body().string();
            KbApiResponse<KbSearchResponse> apiResponse = objectMapper.readValue(
                    responseBody,
                    new TypeReference<KbApiResponse<KbSearchResponse>>() {
                    });
            if (apiResponse == null || !apiResponse.isSuccess() || apiResponse.getData() == null) {
                String message = apiResponse != null ? apiResponse.getMessage() : "empty response";
                throw new IOException("knowledge-base-service returned failure: " + message);
            }
            return apiResponse.getData().getResults() != null ? apiResponse.getData().getResults() : List.of();
        } catch (IOException e) {
            log.warn("调用 knowledge-base-service 失败: query={}, message={}", query, e.getMessage());
            throw e;
        }
    }
}
