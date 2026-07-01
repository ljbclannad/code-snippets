package com.lx.governance.vectormemory.integration.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

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

    private final ChatClient chatClient;

    public DeepSeekService(@Qualifier("openAiChatModel") ChatModel chatModel) {
        this.chatClient = ChatClient.create(chatModel);
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
            // 这里优先追求“结构化、稳定、可重复”的提取，而不是生成的创造性。
            // 注意：DeepSeek 不支持 response_format，当前仍依赖 Spring AI 的 entity/schema 引导。
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    // 低温度：尽量减少模型自由发挥，降低同一输入多次提取结果波动。
                    .withTemperature(0.1)
                    // 收紧采样范围：进一步减少随机性，适合事实/结构化抽取任务。
                    .withTopP(0.8)
                    // 单次只生成一个候选结果，避免多候选带来的额外不确定性和成本。
                    .withN(1)
                    // 不额外鼓励“新内容”，避免模型为了多样性补充对话中并未明确出现的信息。
                    .withPresencePenalty(0.0)
                    // 不额外惩罚重复；提取任务需要稳定输出固定 JSON 字段，没必要做去重复采样。
                    .withFrequencyPenalty(0.0)
                    // 限制输出长度：当前只需要返回较小的 JSON 结构，避免模型在结果后追加无关文本。
                    .withMaxTokens(600)
                    .build();

            // 调用 ChatClient，并让 Spring AI 直接映射为实体
            Prompt prompt = new Prompt(extractionPrompt, options);
            MemoryExtractionResult result = chatClient.prompt(prompt)
                    .call()
                    .entity(MemoryExtractionResult.class);

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
     * @param aiResponse  AI 响应
     * @return 提取 prompt
     */
    private String buildExtractionPrompt(String userMessage, String aiResponse) {
        return """
                你是一个严格、保守的长期记忆提取器。你的任务是从本轮“用户消息 + AI回复”中，只提取适合写入长期记忆的稳定信息。

                提取目标：
                - preference：用户长期偏好、习惯、稳定风格
                - health：明确的健康状况、疾病、过敏、症状、用药禁忌
                - goal：用户明确表达的计划、目标、长期诉求
                - fact：明确的个人事实、身份背景、家庭关系、固定信息
                - context：对后续对话长期有帮助的稳定上下文

                提取原则：
                1. 只提取用户明确表达的信息，或 AI 对用户信息的明确复述确认。
                2. 不要基于语气、常识、上下文做猜测、脑补、延伸推理。
                3. 不要提取短期情绪、一次性安排、寒暄、客套、泛泛表述。
                4. 如果信息模糊、可能有歧义、无法确定是否稳定，则不要提取。
                5. 如果同一信息只是换一种说法，最多保留一条。

                置信度规则：
                - 0.9-1.0：用户直接、明确、无歧义地表达
                - 0.7-0.89：信息较明确，但表述略有间接
                - 0.7 以下：不要输出

                输出要求：
                1. 每条 memory content 必须简洁、客观，不超过 50 字。
                2. content 必须写成“可直接存储的事实”，不要包含“用户可能/似乎/应该/大概”等措辞。
                3. category 只能是：preference、health、goal、fact、context
                4. 如果没有高质量、可长期保存的记忆，返回空数组。
                5. 只返回 JSON，不要返回解释、前后缀、markdown。

                对话内容：
                用户: %s
                AI: %s

                请仅返回符合结构化输出要求的 JSON。
                """.formatted(userMessage, aiResponse);
    }
}
