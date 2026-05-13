package com.lx.governance.vectormemory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.vectormemory.dto.MemoryExtractionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * DeepSeek Service
 *
 * 封装 Spring AI 的 ChatModel，调用 DeepSeek API 进行记忆提取
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * 从对话中提取记忆
     *
     * @param userMessage 用户消息
     * @param aiResponse AI 响应
     * @return 提取的记忆结果
     */
    @Retryable(
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000),
            retryFor = {Exception.class}
    )
    public MemoryExtractionResult extractMemories(String userMessage, String aiResponse) {
        long startTime = System.currentTimeMillis();
        log.info("开始提取记忆 - 用户消息长度: {}, AI响应长度: {}",
                userMessage.length(), aiResponse.length());

        try {
            // 构建提取 prompt
            String extractionPrompt = buildExtractionPrompt(userMessage, aiResponse);

            // 配置 OpenAI 兼容选项（用于 DeepSeek）
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .withResponseFormat(new OpenAiApi.ChatCompletionRequest.ResponseFormat(
                            OpenAiApi.ChatCompletionRequest.ResponseFormat.Type.JSON_OBJECT))
                    .withTemperature(0.7)
                    .withMaxTokens(2000)
                    .build();

            // 调用 ChatModel
            Prompt prompt = new Prompt(extractionPrompt, options);
            String responseContent = chatModel.call(prompt).getResult().getOutput().getContent();

            log.debug("DeepSeek 原始响应: {}", responseContent);

            // 解析 JSON 响应
            MemoryExtractionResult result = objectMapper.readValue(responseContent, MemoryExtractionResult.class);

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
     * 构建记忆提取 Prompt
     *
     * @param userMessage 用户消息
     * @param aiResponse AI 响应
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
