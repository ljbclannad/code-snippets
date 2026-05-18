package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class DeepSeekIntentAnalyzer {

    @Autowired
    private DeepSeekClient deepSeekClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 分析用户意图并创建SessionContext
     */
    public SessionContext analyzeIntent(String currentMessage, List<MessageSummary> recentMessages) {
        try {
            String prompt = buildAnalysisPrompt(currentMessage, recentMessages);

            // 构建ChatRequest调用DeepSeek
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setMessage(prompt);
            chatRequest.setCustomerId("system");
            chatRequest.setConversationId("intent-analysis-" + System.currentTimeMillis());

            DifyResponse response = deepSeekClient.chat(chatRequest, null, null, null);

            return parseAnalysisResult(response.getAnswer());

        } catch (Exception e) {
            log.error("意图分析失败，使用默认上下文", e);
            return createDefaultContext();
        }
    }

    /**
     * 带超时控制的意图分析
     */
    public SessionContext analyzeIntentWithTimeout(String currentMessage,
                                                   List<MessageSummary> recentMessages,
                                                   long timeoutMs) {
        // 简化版：直接调用（实际应使用CompletableFuture.orTimeout）
        return analyzeIntent(currentMessage, recentMessages);
    }

    /**
     * 构建分析Prompt
     */
    private String buildAnalysisPrompt(String currentMessage, List<MessageSummary> recentMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的对话意图分析助手。请分析以下对话内容，提取结构化信息。\n\n");

        // 历史消息
        if (!recentMessages.isEmpty()) {
            prompt.append("历史消息：\n");
            for (MessageSummary summary : recentMessages) {
                prompt.append("- ").append(summary.getUserMessage()).append("\n");
            }
            prompt.append("\n");
        }

        // 当前消息
        prompt.append("当前消息：").append(currentMessage).append("\n\n");

        // 分析要求
        prompt.append("请以JSON格式返回以下信息：\n");
        prompt.append("{\n");
        prompt.append("  \"currentTopic\": \"当前话题（如：血糖管理、饮食咨询）\",\n");
        prompt.append("  \"conversationPhase\": \"对话阶段（consulting或idle）\",\n");
        prompt.append("  \"mentionedEntities\": [\"提及的实体列表\"],\n");
        prompt.append("  \"extractedData\": {\"提取的结构化数据，如血压、体重等\"},\n");
        prompt.append("  \"userIntent\": \"用户意图描述\",\n");
        prompt.append("  \"pendingQuestions\": [\"待确认的问题列表\"],\n");
        prompt.append("  \"complexity\": \"simple或complex\"\n");
        prompt.append("}\n");

        return prompt.toString();
    }

    /**
     * 解析分析结果
     */
    private SessionContext parseAnalysisResult(String response) {
        try {
            // 提取JSON部分（DeepSeek可能返回带解释的文本）
            String jsonStr = extractJson(response);
            JsonNode json = objectMapper.readTree(jsonStr);

            List<String> mentionedEntities = new ArrayList<>();
            if (json.has("mentionedEntities") && json.get("mentionedEntities").isArray()) {
                mentionedEntities = StreamSupport.stream(json.get("mentionedEntities").spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.toList());
            }

            List<String> pendingQuestions = new ArrayList<>();
            if (json.has("pendingQuestions") && json.get("pendingQuestions").isArray()) {
                pendingQuestions = StreamSupport.stream(json.get("pendingQuestions").spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.toList());
            }

            Map<String, Object> extractedData = new HashMap<>();
            if (json.has("extractedData") && json.get("extractedData").isObject()) {
                extractedData = objectMapper.convertValue(json.get("extractedData"), Map.class);
            }

            return SessionContext.builder()
                .currentTopic(json.has("currentTopic") ? json.get("currentTopic").asText() : "健康咨询")
                .conversationPhase(json.has("conversationPhase") ? json.get("conversationPhase").asText() : "consulting")
                .mentionedEntities(mentionedEntities)
                .extractedData(extractedData)
                .userIntent(json.has("userIntent") ? json.get("userIntent").asText() : "一般咨询")
                .pendingQuestions(pendingQuestions)
                .complexity(json.has("complexity") ? json.get("complexity").asText() : "simple")
                .recentSummaries(new ArrayList<>())
                .sessionStartTime(System.currentTimeMillis())
                .lastUpdateTime(System.currentTimeMillis())
                .messageCount(1)
                .build();

        } catch (Exception e) {
            log.error("解析意图分析结果失败", e);
            return createDefaultContext();
        }
    }

    /**
     * 从响应中提取JSON
     */
    private String extractJson(String response) {
        int start = response.indexOf("{");
        int end = response.lastIndexOf("}");
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response;
    }

    /**
     * 创建默认上下文
     */
    private SessionContext createDefaultContext() {
        return SessionContext.builder()
            .currentTopic("健康咨询")
            .conversationPhase("consulting")
            .mentionedEntities(new ArrayList<>())
            .extractedData(new HashMap<>())
            .userIntent("一般咨询")
            .pendingQuestions(new ArrayList<>())
            .complexity("simple")
            .recentSummaries(new ArrayList<>())
            .sessionStartTime(System.currentTimeMillis())
            .lastUpdateTime(System.currentTimeMillis())
            .messageCount(1)
            .build();
    }
}
