package com.lx.governance.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.knowledgebase.dto.MetadataExtractionResult;
import com.lx.governance.knowledgebase.dto.QueryRewriteResult;
import com.lx.governance.knowledgebase.dto.RerankResult;
import com.lx.governance.knowledgebase.dto.SearchResult;
import com.lx.governance.knowledgebase.dto.SemanticDedupResult;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekKnowledgeService {

    @Value("${knowledge-base.deepseek.api-key:${DEEPSEEK_API_KEY:}}")
    private String apiKey;

    @Value("${knowledge-base.deepseek.base-url:https://api.deepseek.com/v1}")
    private String baseUrl;

    @Value("${knowledge-base.deepseek.model:deepseek-chat}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public MetadataExtractionResult extractMetadata(KnowledgeDocument document, String content) {
        String prompt = """
                你是知识库文档治理助手。请根据文档内容提取结构化元数据。
                必须返回严格 JSON，不要附加解释。

                已知人工元数据：
                标题: %s
                标签: %s
                业务域: %s
                来源: %s
                适用范围: %s
                可信级别: %s

                文档正文：
                %s

                输出格式：
                {
                  "summary": "200字以内摘要",
                  "keywords": ["关键词1", "关键词2"],
                  "suggestedTags": ["建议标签1", "建议标签2"],
                  "extendedMetadata": {
                    "topic": "主题",
                    "audience": "适用对象",
                    "qualityHint": "质量提示"
                  }
                }
                """.formatted(
                document.getTitle(),
                document.getTags(),
                document.getBusinessDomain(),
                document.getSource(),
                document.getApplicableScope(),
                document.getTrustLevel(),
                truncate(content, 6000)
        );
        return callForJson(prompt, MetadataExtractionResult.class);
    }

    public SemanticDedupResult detectDuplicate(KnowledgeDocument current,
                                               String currentSummary,
                                               KnowledgeDocument candidate,
                                               String candidateSummary) {
        String prompt = """
                你是知识库重复文档判定助手。请判断两个文档是否语义上高度重复。
                只返回 JSON，不要附加解释。

                当前文档：
                标题: %s
                标签: %s
                业务域: %s
                摘要: %s

                候选文档：
                docId: %s
                标题: %s
                标签: %s
                业务域: %s
                摘要: %s

                输出格式：
                {
                  "duplicate": true,
                  "score": 0.93,
                  "reason": "高度重复的治疗指南",
                  "matchedDocId": "%s"
                }
                """.formatted(
                current.getTitle(), current.getTags(), current.getBusinessDomain(), truncate(currentSummary, 1500),
                candidate.getDocId(), candidate.getTitle(), candidate.getTags(), candidate.getBusinessDomain(), truncate(candidateSummary, 1500),
                candidate.getDocId()
        );
        return callForJson(prompt, SemanticDedupResult.class);
    }

    public QueryRewriteResult rewriteQuery(String query) {
        String prompt = """
                你是知识库检索查询改写助手。请把用户查询改写成更适合检索的表达。
                只返回 JSON，不要附加解释。

                用户查询: %s

                输出格式：
                {
                  "rewrittenQuery": "改写后的查询",
                  "expansionTerms": ["扩展词1", "扩展词2"]
                }
                """.formatted(query);
        return callForJson(prompt, QueryRewriteResult.class);
    }

    public RerankResult rerank(String query, List<SearchResult> candidates) {
        StringBuilder candidateText = new StringBuilder();
        for (SearchResult candidate : candidates) {
            candidateText.append("chunkId=").append(candidate.getChunkId()).append("\n")
                    .append("title=").append(candidate.getTitle()).append("\n")
                    .append("tags=").append(candidate.getTags()).append("\n")
                    .append("businessDomain=").append(candidate.getBusinessDomain()).append("\n")
                    .append("source=").append(candidate.getSource()).append("\n")
                    .append("trustLevel=").append(candidate.getTrustLevel()).append("\n")
                    .append("content=").append(truncate(candidate.getContent(), 1000)).append("\n\n");
        }

        String prompt = """
                你是知识库检索重排助手。请根据用户查询对候选片段进行相关性排序。
                只返回 JSON，不要附加解释。

                用户查询: %s

                候选内容：
                %s

                输出格式：
                {
                  "items": [
                    {
                      "chunkId": "chunk-id",
                      "score": 0.95,
                      "reason": "直接回答了治疗方案"
                    }
                  ]
                }
                """.formatted(query, candidateText);
        return callForJson(prompt, RerankResult.class);
    }

    private <T> T callForJson(String prompt, Class<T> responseType) {
        if (!isConfigured()) {
            throw new IllegalStateException("DeepSeek API key 未配置");
        }

        try {
            String url = baseUrl + "/chat/completions";
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            requestBody.put("temperature", 0.2);
            requestBody.put("max_tokens", 2000);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            String content = objectMapper.readTree(response.getBody())
                    .path("choices").get(0).path("message").path("content").asText();

            return objectMapper.readValue(extractJson(content), responseType);
        } catch (Exception e) {
            log.error("调用 DeepSeek 失败: {}", e.getMessage(), e);
            throw new RuntimeException("调用 DeepSeek 失败: " + e.getMessage(), e);
        }
    }

    private String extractJson(String response) {
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        } else if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) {
                return response.substring(start, end).trim();
            }
        }
        return response.trim();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
