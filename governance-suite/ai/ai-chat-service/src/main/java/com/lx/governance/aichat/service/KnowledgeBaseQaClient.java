package com.lx.governance.aichat.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.lx.governance.aichat.dto.kb.KbApiResponse;
import com.lx.governance.aichat.dto.kb.KbSearchRequest;
import com.lx.governance.aichat.dto.kb.KbSearchResponse;
import com.lx.governance.aichat.dto.kb.KbSearchResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class KnowledgeBaseQaClient {

    private final RestClient restClient;
    private final boolean enabled;
    private final String kbId;

    public KnowledgeBaseQaClient(
            @Qualifier("knowledgeBaseRestClient") RestClient restClient,
            @Value("${ai-chat.kb.enabled:true}") boolean enabled,
            @Value("${ai-chat.kb.kb-id:}") String kbId) {
        this.restClient = restClient;
        this.enabled = enabled;
        this.kbId = kbId;
    }

    public boolean isEnabled() {
        return enabled && kbId != null && !kbId.isBlank();
    }

    public List<KbSearchResult> search(KbSearchRequest request) {
        if (!isEnabled()) {
            return List.of();
        }
        KbApiResponse<KbSearchResponse> response = restClient.post()
                .uri("/api/kb/{kbId}/search", kbId)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<KbApiResponse<KbSearchResponse>>() {});
        if (response == null || !response.isSuccess() || response.getData() == null || response.getData().getResults() == null) {
            log.warn("知识库问答检索返回空结果");
            return List.of();
        }
        return response.getData().getResults();
    }
}
