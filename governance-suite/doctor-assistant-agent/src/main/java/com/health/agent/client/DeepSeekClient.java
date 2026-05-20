package com.health.agent.client;

import com.alibaba.fastjson2.JSON;
import com.health.agent.model.Message;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DeepSeek API 客户端
 * 用于调用 DeepSeek API 进行 ReAct Agent 推理
 */
@Slf4j
@Component
public class DeepSeekClient {

    private final ChatModel chatModel;

    @Value("${agent.deepseek.model:deepseek-chat}")
    private String model;

    @Value("${agent.deepseek.temperature:0.7}")
    private double temperature;

    @Value("${agent.deepseek.max-tokens:2000}")
    private int maxTokens;

    public DeepSeekClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 调用 DeepSeek API 进行对话
     *
     * @param systemPrompt System Prompt
     * @param messages     对话历史
     * @return DeepSeek 响应
     */
    public DeepSeekResponse chat(String systemPrompt, List<Message> messages) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("调用 DeepSeek API, 消息数量: {}", messages.size());

            // 构建 Spring AI 消息列表
            List<org.springframework.ai.chat.messages.Message> aiMessages = new ArrayList<>();

            // 添加 System Message
            aiMessages.add(new SystemMessage(systemPrompt));

            // 添加对话历史
            for (Message msg : messages) {
                if ("user".equals(msg.getRole())) {
                    aiMessages.add(new UserMessage(msg.getContent()));
                } else if ("assistant".equals(msg.getRole())) {
                    aiMessages.add(new AssistantMessage(msg.getContent()));
                }
            }

            // 构建 Prompt
            Prompt prompt = new Prompt(
                aiMessages,
                OpenAiChatOptions.builder()
                    .withTemperature(temperature)
                    .withMaxTokens(maxTokens)
                    .build()
            );

            // 调用 ChatModel
            ChatResponse response = chatModel.call(prompt);
            String content = response.getResult().getOutput().getContent();

            // 提取 token 使用情况
            var usage = response.getMetadata().getUsage();
            if (usage == null) {
                log.warn("DeepSeek API 未返回 token 使用信息");
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("DeepSeek API 调用完成, 耗时: {}ms, tokens: prompt={}, completion={}, total={}",
                duration,
                usage != null ? usage.getPromptTokens() : 0,
                usage != null ? usage.getGenerationTokens() : 0,
                usage != null ? usage.getTotalTokens() : 0);

            // 构建响应
            DeepSeekResponse deepSeekResponse = new DeepSeekResponse();
            deepSeekResponse.setContent(content);
            if (usage != null) {
                deepSeekResponse.setPromptTokens(usage.getPromptTokens().intValue());
                deepSeekResponse.setCompletionTokens(usage.getGenerationTokens().intValue());
                deepSeekResponse.setTotalTokens(usage.getTotalTokens().intValue());
            }
            deepSeekResponse.setDuration(duration);

            return deepSeekResponse;

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("DeepSeek API 调用失败, 耗时: {}ms", duration, e);
            throw new RuntimeException("DeepSeek API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * DeepSeek API 响应
     */
    @Data
    public static class DeepSeekResponse {
        /**
         * 响应内容
         */
        private String content;

        /**
         * Prompt tokens
         */
        private int promptTokens;

        /**
         * Completion tokens
         */
        private int completionTokens;

        /**
         * Total tokens
         */
        private int totalTokens;

        /**
         * 调用耗时（毫秒）
         */
        private long duration;
    }
}
