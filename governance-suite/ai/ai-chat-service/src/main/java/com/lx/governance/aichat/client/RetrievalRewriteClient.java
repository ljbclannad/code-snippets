package com.lx.governance.aichat.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.RetrievalRewriteResult;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalRewriteClient {

    private static final Set<String> ALLOWED_MEMORY_TYPES = Set.of(
            "SYMPTOM", "ALLERGY", "MEDICATION", "DIET", "PREFERENCE", "OTHER"
    );

    private static final String SYSTEM_PROMPT = """
            你是长期记忆检索查询改写器。
            你的唯一任务是把用户当前问题改写成适合向量检索的短查询。
            只输出 JSON，不要解释，不要回答用户问题，不要添加 markdown 代码块。
            优先保留主题、意图、疾病、目标、时间、禁忌、行为偏好等关键信号。
            如果原问题已经足够明确，就尽量少改写。
            JSON 字段固定为：
            {
              "rewrittenQuery": "单行检索 query",
              "keywords": ["关键词1", "关键词2"],
              "suggestedMemoryTypes": ["DIET", "SYMPTOM"],
              "suggestedRecentDays": 90,
              "contextDependent": true
            }
            """;

    private final ChatModel chatModel;
    private final ContextBudgetProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String rewrite(String message, SessionContext sessionContext, UserProfileDTO userProfile, String baselineQuery) {
        return rewriteStructured(message, sessionContext, userProfile, baselineQuery).getRewrittenQuery();
    }

    public RetrievalRewriteResult rewriteStructured(
            String message,
            SessionContext sessionContext,
            UserProfileDTO userProfile,
            String baselineQuery) {
        try {
            Prompt prompt = new Prompt(
                    List.of(
                            new SystemMessage(SYSTEM_PROMPT),
                            new UserMessage(buildUserPrompt(message, sessionContext, userProfile, baselineQuery))),
                    OpenAiChatOptions.builder()
                            .withTemperature(properties.getMemory().getRewrite().getTemperature())
                            .withMaxTokens(properties.getMemory().getRewrite().getMaxTokens())
                            .build());

            String content = chatModel.call(prompt).getResult().getOutput().getContent();
            return parseResult(content, baselineQuery);
        } catch (Exception e) {
            log.warn("LLM retrieval rewrite failed, falling back to rule-based query", e);
            return RetrievalRewriteResult.builder()
                    .rewrittenQuery("")
                    .rawResponse("")
                    .build();
        }
    }

    private String buildUserPrompt(
            String message,
            SessionContext sessionContext,
            UserProfileDTO userProfile,
            String baselineQuery) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("原始用户问题: ").append(nullToEmpty(message)).append("\n");
        prompt.append("规则版检索 query: ").append(nullToEmpty(baselineQuery)).append("\n");

        if (sessionContext != null) {
            prompt.append("当前话题: ").append(nullToEmpty(sessionContext.getCurrentTopic())).append("\n");
            prompt.append("用户意图: ").append(nullToEmpty(sessionContext.getUserIntent())).append("\n");
            prompt.append("最近摘要: ").append(buildSummaries(sessionContext.getRecentSummaries())).append("\n");
        }

        prompt.append("核心画像: ").append(buildProfile(userProfile)).append("\n");
        prompt.append("输出要求: 返回 JSON；rewrittenQuery 长度不超过 ")
                .append(properties.getMemory().getRewrite().getMaxQueryLength())
                .append(" 个字符；keywords 不超过 5 个；suggestedMemoryTypes 只能从 ")
                .append(ALLOWED_MEMORY_TYPES)
                .append(" 中选择");
        return prompt.toString();
    }

    private String buildSummaries(List<MessageSummary> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "";
        }
        List<String> values = new ArrayList<>();
        int start = Math.max(0, summaries.size() - 2);
        for (int i = start; i < summaries.size(); i++) {
            MessageSummary summary = summaries.get(i);
            if (summary != null && summary.getUserMessage() != null && !summary.getUserMessage().isBlank()) {
                values.add(summary.getUserMessage().trim());
            }
        }
        return String.join("；", values);
    }

    private String buildProfile(UserProfileDTO userProfile) {
        if (userProfile == null) {
            return "";
        }

        List<String> values = new ArrayList<>();
        appendMapValues(values, userProfile.getHealthProfile(), "goal", "goals", "disease", "chronicDisease", "chronicDiseases");
        appendMapValues(values, userProfile.getBehaviorPreferences(), "dietPreference", "dietaryPreferences", "exerciseFrequency", "exerciseHabits");
        return String.join("；", values);
    }

    private void appendMapValues(List<String> values, Map<String, Object> source, String... keys) {
        if (source == null) {
            return;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof List<?> list) {
                if (!list.isEmpty()) {
                    values.add(key + ":" + list);
                }
            } else {
                values.add(key + ":" + value);
            }
        }
    }

    private RetrievalRewriteResult parseResult(String content, String baselineQuery) {
        String sanitized = sanitize(content);
        if (sanitized.isEmpty()) {
            return RetrievalRewriteResult.builder()
                    .rewrittenQuery("")
                    .rawResponse("")
                    .build();
        }

        try {
            String json = extractJson(sanitized);
            JsonNode node = objectMapper.readTree(json);
            String rewrittenQuery = sanitize(node.path("rewrittenQuery").asText(""));
            List<String> keywords = parseKeywords(node.path("keywords"));
            Set<String> memoryTypes = parseMemoryTypes(node.path("suggestedMemoryTypes"));
            Integer recentDays = node.hasNonNull("suggestedRecentDays") ? node.get("suggestedRecentDays").asInt() : null;
            Boolean contextDependent = node.has("contextDependent") ? node.get("contextDependent").asBoolean() : null;

            return RetrievalRewriteResult.builder()
                    .rewrittenQuery(rewrittenQuery)
                    .keywords(keywords)
                    .suggestedMemoryTypes(memoryTypes)
                    .suggestedRecentDays(recentDays)
                    .contextDependent(contextDependent)
                    .rawResponse(sanitized)
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse rewrite JSON, fallback to plain query", e);
            return RetrievalRewriteResult.builder()
                    .rewrittenQuery(sanitized)
                    .rawResponse(sanitized)
                    .build();
        }
    }

    private String sanitize(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceAll("```json|```", " ")
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("^['\"`]+|['\"`]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return content;
    }

    private List<String> parseKeywords(JsonNode node) {
        List<String> keywords = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return keywords;
        }
        for (JsonNode item : node) {
            String value = sanitize(item.asText(""));
            if (!value.isBlank()) {
                keywords.add(value);
            }
            if (keywords.size() >= 5) {
                break;
            }
        }
        return keywords;
    }

    private Set<String> parseMemoryTypes(JsonNode node) {
        Set<String> memoryTypes = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return memoryTypes;
        }
        for (JsonNode item : node) {
            String value = sanitize(item.asText("")).toUpperCase();
            if (ALLOWED_MEMORY_TYPES.contains(value)) {
                memoryTypes.add(value);
            }
        }
        return memoryTypes;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
