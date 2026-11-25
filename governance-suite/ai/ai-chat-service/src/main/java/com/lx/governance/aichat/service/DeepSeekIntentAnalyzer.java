package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.dto.ChatGenerationResult;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.metrics.AiChatMetrics;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DeepSeekIntentAnalyzer {

    private static final String DEFAULT_TOPIC = "健康咨询";
    private static final String DEFAULT_PHASE = "consulting";
    private static final String DEFAULT_INTENT = SessionContext.UNKNOWN_INTENT;
    private static final String DEFAULT_COMPLEXITY = "simple";
    private static final Set<String> ALLOWED_INTENTS = Set.of(
            "casual_chat",
            "fact_query",
            "general_health_consultation",
            "profile_follow_up_answer",
            "follow_up_trend_analysis",
            "risk_review_request",
            "next_action_recommendation");

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private AiChatMetrics metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 分析用户意图并创建SessionContext
     */
    public SessionContext analyzeIntent(String currentMessage, List<MessageSummary> recentMessages) {
        return analyzeIntent(currentMessage, recentMessages, null);
    }

    private SessionContext analyzeIntent(String currentMessage, List<MessageSummary> recentMessages, Long timeoutMs) {
        try {
            String prompt = buildAnalysisPrompt(currentMessage, recentMessages);

            // 构建ChatRequest调用DeepSeek
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setMessage(prompt);
            chatRequest.setCustomerId("system");
            chatRequest.setConversationId("intent-analysis-" + System.currentTimeMillis());

            ChatGenerationResult response = timeoutMs != null
                    ? deepSeekClient.chatWithTimeout(chatRequest, timeoutMs)
                    : deepSeekClient.chat(chatRequest, null, null, Collections.<MemoryItemDTO>emptyList());

            return parseAnalysisResult(response.getAnswer());

        } catch (Exception e) {
            log.warn("意图分析失败，本轮保留未确定意图并跳过画像补全", e);
            return createDefaultContext();
        }
    }

    /**
     * 带超时控制的意图分析
     */
    public SessionContext analyzeIntentWithTimeout(String currentMessage,
            List<MessageSummary> recentMessages,
            long timeoutMs) {
        return analyzeIntent(currentMessage, recentMessages, timeoutMs);
    }

    /**
     * 构建分析Prompt
     */
    private String buildAnalysisPrompt(String currentMessage, List<MessageSummary> recentMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的医疗健康对话意图分析助手。");
        prompt.append("你的输出会被后续任务路由使用，因此必须优先做保守、可落地的分类，不要过度推断。");
        prompt.append("如果当前信息不足，默认判定为普通咨询，而不是高风险或复杂任务。\n\n");

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
        prompt.append("请结合以下任务路由约束进行分析：\n");
        prompt.append("1. 后续只会升级三类 Agent 任务：患者随访趋势分析、风险分层/人工复核、下一步随访动作建议。\n");
        prompt.append("2. 如果用户只是寒暄、感谢、简单事实问答、泛化咨询、信息不足的短句，userIntent 必须保持在普通对话范围，不要拔高。\n");
        prompt.append("3. 只有在出现明确趋势分析诉求（如“分析最近两周变化”）、明确风险/恶化/转人工信号、或结合上下文明确询问下一步处理建议时，才可标记为对应升级意图。\n");
        prompt.append("4. 如果当前消息更像是在回答系统追问或补充资料，pendingQuestions 应帮助后续继续补全画像，不要误判为 Agent 任务。\n");
        prompt.append("5. complexity 只有在涉及多指标、多时间段对比、风险判断、方案建议、需要结合历史上下文时才输出 complex，否则输出 simple。\n\n");

        prompt.append("userIntent 只能从以下枚举中选择一个：\n");
        prompt.append("- casual_chat：寒暄、感谢、礼貌回复、无明确任务目标\n");
        prompt.append("- fact_query：概念解释、是什么、介绍一下、简单知识问答\n");
        prompt.append("- general_health_consultation：一般健康咨询，但不足以触发 Agent 升级\n");
        prompt.append("- profile_follow_up_answer：在回答系统追问、补充个人情况或画像字段\n");
        prompt.append("- follow_up_trend_analysis：明确要求分析一段时间内的指标/症状/体重等变化趋势\n");
        prompt.append("- risk_review_request：明确提到风险、恶化、严重、是否转人工/转医生/复核\n");
        prompt.append("- next_action_recommendation：明确要求基于当前情况给出下一步处理或随访建议\n\n");

        prompt.append("下面是 few-shot 样例，请严格参考这些边界，不要自行扩大发散：\n");
        prompt.append("样例1\n");
        prompt.append("当前消息：你好，在吗\n");
        prompt.append("输出：{\"currentTopic\":\"寒暄\",\"conversationPhase\":\"idle\",\"mentionedEntities\":[],\"extractedData\":{},\"userIntent\":\"casual_chat\",\"pendingQuestions\":[],\"complexity\":\"simple\"}\n\n");

        prompt.append("样例2\n");
        prompt.append("当前消息：糖化血红蛋白是什么意思\n");
        prompt.append("输出：{\"currentTopic\":\"糖化血红蛋白解释\",\"conversationPhase\":\"consulting\",\"mentionedEntities\":[\"糖化血红蛋白\"],\"extractedData\":{},\"userIntent\":\"fact_query\",\"pendingQuestions\":[],\"complexity\":\"simple\"}\n\n");

        prompt.append("样例3\n");
        prompt.append("当前消息：我最近血糖有点高，早餐应该怎么吃\n");
        prompt.append("输出：{\"currentTopic\":\"血糖与早餐饮食\",\"conversationPhase\":\"consulting\",\"mentionedEntities\":[\"血糖\",\"早餐\"],\"extractedData\":{},\"userIntent\":\"general_health_consultation\",\"pendingQuestions\":[],\"complexity\":\"simple\"}\n\n");

        prompt.append("样例4\n");
        prompt.append("历史消息：- 最近一周体重变化怎么样\n");
        prompt.append("当前消息：这周掉了1公斤\n");
        prompt.append("输出：{\"currentTopic\":\"体重变化补充\",\"conversationPhase\":\"consulting\",\"mentionedEntities\":[\"体重\"],\"extractedData\":{\"weightChange\":\"1公斤\"},\"userIntent\":\"profile_follow_up_answer\",\"pendingQuestions\":[],\"complexity\":\"simple\"}\n\n");

        prompt.append("样例5\n");
        prompt.append("当前消息：帮我分析一下最近两周体重和血糖的变化趋势\n");
        prompt.append("输出：{\"currentTopic\":\"体重和血糖趋势分析\",\"conversationPhase\":\"consulting\",\"mentionedEntities\":[\"体重\",\"血糖\"],\"extractedData\":{},\"userIntent\":\"follow_up_trend_analysis\",\"pendingQuestions\":[],\"complexity\":\"complex\"}\n\n");

        prompt.append("样例6\n");
        prompt.append("当前消息：患者这几天状态恶化，需不需要转人工医生复核风险\n");
        prompt.append("输出：{\"currentTopic\":\"风险复核评估\",\"conversationPhase\":\"consulting\",\"mentionedEntities\":[\"患者\",\"医生\",\"风险\"],\"extractedData\":{},\"userIntent\":\"risk_review_request\",\"pendingQuestions\":[],\"complexity\":\"complex\"}\n\n");

        prompt.append("样例7\n");
        prompt.append("当前消息：结合最近随访情况，下一步我该怎么做\n");
        prompt.append("输出：{\"currentTopic\":\"下一步随访建议\",\"conversationPhase\":\"consulting\",\"mentionedEntities\":[\"随访\"],\"extractedData\":{},\"userIntent\":\"next_action_recommendation\",\"pendingQuestions\":[],\"complexity\":\"complex\"}\n\n");

        prompt.append("样例8\n");
        prompt.append("当前消息：最近还行\n");
        prompt.append("输出：{\"currentTopic\":\"近况反馈\",\"conversationPhase\":\"consulting\",\"mentionedEntities\":[],\"extractedData\":{},\"userIntent\":\"general_health_consultation\",\"pendingQuestions\":[\"最近主要是哪些指标或症状还行？\"],\"complexity\":\"simple\"}\n\n");

        prompt.append("请以 JSON 格式返回以下信息：\n");
        prompt.append("{\n");
        prompt.append("  \"currentTopic\": \"当前话题（如：血糖管理、饮食咨询）\",\n");
        prompt.append("  \"conversationPhase\": \"对话阶段（consulting或idle）\",\n");
        prompt.append("  \"mentionedEntities\": [\"提及的实体列表\"],\n");
        prompt.append("  \"extractedData\": {\"提取的结构化数据，如血压、体重等\"},\n");
        prompt.append("  \"userIntent\": \"以上枚举之一\",\n");
        prompt.append("  \"pendingQuestions\": [\"待确认的问题列表\"],\n");
        prompt.append("  \"complexity\": \"simple或complex\"\n");
        prompt.append("}\n");

        prompt.append("额外要求：\n");
        prompt.append("- 只返回 JSON，不要附加解释。\n");
        prompt.append("- 不要虚构用户未提到的医学结论、检查结果或风险。\n");
        prompt.append("- currentTopic 要贴近用户表达，不要写得过大过泛。\n");
        prompt.append("- conversationPhase 只有在明显是闲聊时输出 idle，其余默认 consulting。\n");
        prompt.append("- pendingQuestions 只保留真正缺失且会影响后续判断的关键信息，最多 3 个。\n");

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
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .collect(Collectors.toList());
            }

            List<String> pendingQuestions = new ArrayList<>();
            if (json.has("pendingQuestions") && json.get("pendingQuestions").isArray()) {
                pendingQuestions = StreamSupport.stream(json.get("pendingQuestions").spliterator(), false)
                        .map(JsonNode::asText)
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .limit(3)
                        .collect(Collectors.toList());
            }

            Map<String, Object> extractedData = new HashMap<>();
            if (json.has("extractedData") && json.get("extractedData").isObject()) {
                extractedData = objectMapper.convertValue(json.get("extractedData"), Map.class);
            }

            return SessionContext.builder()
                    .currentTopic(sanitizeText(
                            json.has("currentTopic") ? json.get("currentTopic").asText() : null,
                            DEFAULT_TOPIC))
                    .conversationPhase(
                            normalizeConversationPhase(json.has("conversationPhase")
                                    ? json.get("conversationPhase").asText()
                                    : null))
                    .mentionedEntities(mentionedEntities)
                    .extractedData(extractedData)
                    .userIntent(normalizeIntent(json.has("userIntent") ? json.get("userIntent").asText() : null))
                    .pendingQuestions(pendingQuestions)
                    .complexity(normalizeComplexity(json.has("complexity") ? json.get("complexity").asText() : null))
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
        metrics.recordIntentAnalysisFallback();
        return SessionContext.builder()
                .currentTopic(DEFAULT_TOPIC)
                .conversationPhase(DEFAULT_PHASE)
                .mentionedEntities(new ArrayList<>())
                .extractedData(new HashMap<>())
                .userIntent(DEFAULT_INTENT)
                .pendingQuestions(new ArrayList<>())
                .complexity(DEFAULT_COMPLEXITY)
                .recentSummaries(new ArrayList<>())
                .sessionStartTime(System.currentTimeMillis())
                .lastUpdateTime(System.currentTimeMillis())
                .messageCount(1)
                .build();
    }

    private String normalizeIntent(String rawIntent) {
        String normalized = sanitizeText(rawIntent, DEFAULT_INTENT).toLowerCase();
        if (ALLOWED_INTENTS.contains(normalized)) {
            return normalized;
        }
        metrics.recordIntentAnalysisFallback();
        log.warn("意图分析返回了未注册 intent，本轮保留未确定意图 - rawIntent: {}", rawIntent);
        return DEFAULT_INTENT;
    }

    private String normalizeConversationPhase(String rawPhase) {
        String normalized = sanitizeText(rawPhase, DEFAULT_PHASE).toLowerCase();
        return "idle".equals(normalized) ? "idle" : DEFAULT_PHASE;
    }

    private String normalizeComplexity(String rawComplexity) {
        String normalized = sanitizeText(rawComplexity, DEFAULT_COMPLEXITY).toLowerCase();
        return "complex".equals(normalized) ? "complex" : DEFAULT_COMPLEXITY;
    }

    private String sanitizeText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }
}
