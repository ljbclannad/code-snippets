package com.lx.governance.vectormemory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 直接调用 DeepSeek API（不使用 Spring AI）
 */
@Slf4j
@Service
public class DeepSeekDirectService {

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeepSeekDirectService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Retryable(
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000),
            retryFor = {Exception.class}
    )
    public MemoryExtractionResult extractMemories(String userMessage, String aiResponse) {
        long startTime = System.currentTimeMillis();
        log.info("开始提取记忆（直接调用）- 用户消息长度: {}, AI响应长度: {}",
                userMessage.length(), aiResponse.length());

        try {
            String extractionPrompt = buildExtractionPrompt(userMessage, aiResponse);

            // 构建请求
            String url = baseUrl + "/chat/completions";
            log.info("请求 URL: {}", url);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "deepseek-chat");
            requestBody.put("messages", List.of(
                Map.of("role", "user", "content", extractionPrompt)
            ));
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 2000);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.debug("发送请求到 DeepSeek API");
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            log.debug("收到响应: status={}, body={}", response.getStatusCode(),
                    response.getBody() != null ? response.getBody().substring(0, Math.min(200, response.getBody().length())) : "null");

            // 解析响应
            JsonNode root = objectMapper.readTree(response.getBody());
            String content = root.path("choices").get(0).path("message").path("content").asText();

            // 提取 JSON
            String jsonContent = extractJsonFromResponse(content);
            MemoryExtractionResult result = objectMapper.readValue(jsonContent, MemoryExtractionResult.class);

            long duration = System.currentTimeMillis() - startTime;
            int memoryCount = result.getMemories() != null ? result.getMemories().size() : 0;
            log.info("记忆提取完成 - 提取数量: {}, 耗时: {}ms", memoryCount, duration);

            return result;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("记忆提取失败 - 耗时: {}ms, 错误: {}", duration, e.getMessage(), e);
            throw new RuntimeException("记忆提取失败: " + e.getMessage(), e);
        }
    }

    private String buildExtractionPrompt(String userMessage, String aiResponse) {
        return String.format("""
            你是一个专业的记忆提取助手。请从以下用户和 AI 的对话中提取值得记住的关键信息。

            **提取目标：**
            - 用户偏好（喜好、习惯、风格）
            - 健康状况（疾病、过敏、身体状态）
            - 个人目标（计划、愿望、目标）
            - 重要事实（日期、地点、人物关系）
            - 情感状态（情绪、态度、感受）

            **分类标准：**
            - PREFERENCE: 用户的喜好、习惯、偏好
            - HEALTH: 健康相关信息（疾病、症状、用药、过敏）
            - GOAL: 目标、计划、愿望
            - FACT: 重要的事实性信息
            - EMOTION: 情感、情绪、态度

            **置信度评分（0.0-1.0）：**
            - 0.9-1.0: 用户明确表达的信息
            - 0.7-0.9: 可以合理推断的信息
            - 0.5-0.7: 需要进一步确认的信息
            - <0.5: 不应提取

            **去重规则：**
            - 如果信息已在历史记忆中存在，不要重复提取
            - 如果是对已有信息的更新或补充，提取新的部分

            **用户消息：**
            %s

            **AI 响应：**
            %s

            请严格按照以下 JSON 格式输出：
            {
              "memories": [
                {
                  "content": "记忆内容",
                  "type": "PREFERENCE|HEALTH|GOAL|FACT|EMOTION",
                  "confidence": 0.95
                }
              ]
            }

            请严格按照 JSON 格式输出，不要包含任何其他文本。如果没有值得记住的信息，返回空数组。
            """, userMessage, aiResponse);
    }

    private String extractJsonFromResponse(String response) {
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
}
