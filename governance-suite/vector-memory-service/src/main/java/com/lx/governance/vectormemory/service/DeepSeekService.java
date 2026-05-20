package com.lx.governance.vectormemory.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.vectormemory.dto.MemoryExtractionResult;

import lombok.extern.slf4j.Slf4j;

/**
 * DeepSeek Service
 *
 * 封装 Spring AI 的 ChatModel，调用 DeepSeek API 进行记忆提取
 */
@Slf4j
@Service
public class DeepSeekService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public DeepSeekService(@Qualifier("openAiChatModel") ChatModel chatModel,
            ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 从对话中提取记忆
     *
     * @param userMessage 用户消息
     * @param aiResponse  AI 响应
     * @return 提取的记忆结果
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 2000), retryFor = { Exception.class })
    public MemoryExtractionResult extractMemories(String userMessage, String aiResponse) {
        long startTime = System.currentTimeMillis();
        log.info("开始提取记忆 - 用户消息长度: {}, AI响应长度: {}",
                userMessage.length(), aiResponse.length());

        try {
            // 构建提取 prompt
            String extractionPrompt = buildExtractionPrompt(userMessage, aiResponse);

            // 配置 OpenAI 兼容选项（用于 DeepSeek）
            // 注意：DeepSeek 不支持 response_format，改用 Prompt 引导返回 JSON
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .withTemperature(0.7)
                    .withMaxTokens(2000)
                    .build();

            // 调用 ChatModel
            Prompt prompt = new Prompt(extractionPrompt, options);
            String responseContent = chatModel.call(prompt).getResult().getOutput().getContent();

            log.debug("DeepSeek 原始响应: {}", responseContent);

            // 提取 JSON 部分（可能包含 markdown 代码块）
            String jsonContent = extractJsonFromResponse(responseContent);

            // 解析 JSON 响应
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

    /**
     * 从响应中提取 JSON 内容
     */
    private String extractJsonFromResponse(String response) {
        // 如果响应包含 markdown 代码块，提取其中的 JSON
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
        // 否则直接返回原响应
        return response.trim();
    }

    /**
     * 构建记忆提取 Prompt
     *
     * @param userMessage 用户消息
     * @param aiResponse  AI 响应
     * @return 提取 prompt
     */
    private String buildExtractionPrompt(String userMessage, String aiResponse) {
        return """
                你是一个专业的记忆提取助手。请从以下用户和 AI 的对话中提取值得记住的关键信息。

                **提取目标：**
                - 用户偏好（喜好、习惯、风格）
                - 健康状况（疾病、过敏、身体状态）
                - 个人目标（计划、愿望、目标）
                - 重要事实（姓名、职业、家庭、背景）

                **提取要求：**
                1. 简洁明确：每条记忆不超过 50 字
                2. 评估置信度：根据信息的明确程度给出 0.0-1.0 的置信度分数
                3. 分类：将记忆归类为 preference（偏好）、health（健康）、goal（目标）、fact（事实）、context（上下文）
                4. 去重：避免提取重复或相似的信息
                5. 过滤：忽略无关紧要的闲聊内容

                **对话内容：**

                用户: %s

                AI: %s

                **输出格式（JSON）：**
                {
                  "memories": [
                    {
                      "content": "提取的记忆内容",
                      "confidence": 0.95,
                      "category": "preference"
                    }
                  ]
                }

                请严格按照 JSON 格式输出，不要包含任何其他文本。如果没有值得记住的信息，返回空数组。
                """.formatted(userMessage, aiResponse);
    }
}
