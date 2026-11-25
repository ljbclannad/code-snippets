package com.lx.governance.aichat.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.aichat.config.ContextBudgetProperties;
import com.lx.governance.aichat.dto.ConversationMessage;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史管理器
 * 负责查询、格式化和智能截断对话历史
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationHistoryManager {

    private final ContextBudgetProperties properties;
    private final AiChatHistoryMapper chatHistoryMapper;

    /**
     * 获取最近的对话历史
     *
     * @param customerId 客户ID
     * @param conversationId 对话ID
     * @param maxRounds 最多保留轮数
     * @return 对话历史列表
     */
    public List<ConversationMessage> getRecentHistory(String customerId, String conversationId, int maxRounds) {
        try {
            // 查询最近的对话记录
            LambdaQueryWrapper<AiChatHistory> queryWrapper = new LambdaQueryWrapper<>();
            queryWrapper.eq(AiChatHistory::getCustomerId, customerId)
                    .eq(AiChatHistory::getConversationId, conversationId)
                    .eq(AiChatHistory::getIsDeleted, 0)
                    .orderByDesc(AiChatHistory::getCreateTime)
                    .last("LIMIT " + (maxRounds * 2)); // 每轮包含用户消息和AI回复

            List<AiChatHistory> historyList = chatHistoryMapper.selectList(queryWrapper);

            if (historyList.isEmpty()) {
                log.debug("无对话历史 - customerId: {}, conversationId: {}", customerId, conversationId);
                return new ArrayList<>();
            }

            // 转换为 ConversationMessage 列表（按时间正序）
            List<ConversationMessage> messages = new ArrayList<>();
            for (int i = historyList.size() - 1; i >= 0; i--) {
                AiChatHistory history = historyList.get(i);

                // 添加用户消息
                messages.add(ConversationMessage.builder()
                        .role(ConversationMessage.Role.USER)
                        .content(history.getUserMessage())
                        .timestamp(history.getCreateTime())
                        .build());

                // 添加AI回复
                messages.add(ConversationMessage.builder()
                        .role(ConversationMessage.Role.ASSISTANT)
                        .content(history.getAiResponse())
                        .timestamp(history.getCreateTime())
                        .build());
            }

            log.debug("获取对话历史 - customerId: {}, conversationId: {}, 轮数: {}",
                    customerId, conversationId, messages.size() / 2);

            return messages;

        } catch (Exception e) {
            log.error("获取对话历史失败 - customerId: {}, conversationId: {}", customerId, conversationId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 格式化对话历史（受预算约束）
     *
     * @param history 对话历史列表
     * @param budget 可用的 token 预算
     * @return 格式化后的对话历史文本
     */
    public String formatHistory(List<ConversationMessage> history, int budget) {
        if (history == null || history.isEmpty()) {
            return "";
        }

        StringBuilder selected = new StringBuilder();
        int remaining = budget;
        // 按最近问答轮次选择，优先给用户问题留空间，再保留回复片段。
        for (int i = history.size() - 1; i >= 0 && remaining > 0; i--) {
            ConversationMessage latest = history.get(i);
            String answer = getRoleDisplayName(latest.getRole()) + ": " + latest.getContent() + "\n";
            String question = "";
            if (latest.getRole() == ConversationMessage.Role.ASSISTANT && i > 0
                    && history.get(i - 1).getRole() == ConversationMessage.Role.USER) {
                ConversationMessage user = history.get(--i);
                question = "用户: " + user.getContent() + "\n";
            }
            String fullTurn = question + answer;
            if (PromptTokenBudget.estimate(fullTurn) <= remaining) {
                selected.insert(0, fullTurn);
                remaining -= PromptTokenBudget.estimate(fullTurn);
                continue;
            }
            if (selected.isEmpty()) {
                String fittedQuestion = PromptTokenBudget.fit(question, remaining);
                int answerBudget = remaining - PromptTokenBudget.estimate(fittedQuestion);
                selected.append(fittedQuestion).append(PromptTokenBudget.fit(answer, answerBudget));
            }
            break;
        }
        return selected.toString();
    }

    /**
     * 根据预算智能选择对话历史
     *
     * @param messages 完整的对话历史
     * @param budget 可用的 token 预算
     * @return 截断后的对话历史文本
     */
    public String selectConversationHistory(List<ConversationMessage> messages, int budget) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        // 按时间分层
        LocalDateTime now = LocalDateTime.now();
        List<ConversationMessage> shortTerm = new ArrayList<>();
        List<ConversationMessage> midTerm = new ArrayList<>();
        List<ConversationMessage> longTerm = new ArrayList<>();

        int shortTermDays = properties.getMemory().getShortTermDays();
        int midTermDays = properties.getMemory().getMidTermDays();

        for (ConversationMessage message : messages) {
            long daysDiff = java.time.Duration.between(message.getTimestamp(), now).toDays();

            if (daysDiff <= shortTermDays) {
                shortTerm.add(message);
            } else if (daysDiff <= midTermDays) {
                midTerm.add(message);
            } else {
                longTerm.add(message);
            }
        }

        // 优先级：短期 > 中期 > 长期
        List<ConversationMessage> selected = new ArrayList<>();
        int usedTokens = 0;

        // 1. 添加短期对话（全部保留）
        for (ConversationMessage message : shortTerm) {
            int tokens = estimateTokens(message.getContent());
            if (usedTokens + tokens <= budget) {
                selected.add(message);
                usedTokens += tokens;
            } else {
                break;
            }
        }

        // 2. 添加中期对话（采样）
        if (usedTokens < budget) {
            List<ConversationMessage> sampledMidTerm = sampleMessages(midTerm, 0.5);
            for (ConversationMessage message : sampledMidTerm) {
                int tokens = estimateTokens(message.getContent());
                if (usedTokens + tokens <= budget) {
                    selected.add(message);
                    usedTokens += tokens;
                } else {
                    break;
                }
            }
        }

        // 3. 添加长期对话（摘要或跳过）
        if (usedTokens < budget && !longTerm.isEmpty()) {
            String summary = summarizeLongTermHistory(longTerm);
            int tokens = estimateTokens(summary);
            if (usedTokens + tokens <= budget) {
                // 将摘要作为一条消息添加
                selected.add(ConversationMessage.builder()
                        .role(ConversationMessage.Role.ASSISTANT)
                        .content("[历史摘要] " + summary)
                        .timestamp(longTerm.get(0).getTimestamp())
                        .tokenCount(tokens)
                        .build());
            }
        }

        return formatMessages(selected);
    }

    /**
     * 格式化消息列表为文本
     */
    public String formatMessages(List<ConversationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ConversationMessage message : messages) {
            String roleName = getRoleDisplayName(message.getRole());
            sb.append(roleName)
              .append(": ")
              .append(message.getContent())
              .append("\n");
        }
        return sb.toString();
    }

    /**
     * 获取角色的中文显示名称
     */
    private String getRoleDisplayName(ConversationMessage.Role role) {
        return role == ConversationMessage.Role.USER ? "用户" : "助手";
    }

    /**
     * 采样消息（保留指定比例）
     */
    private List<ConversationMessage> sampleMessages(List<ConversationMessage> messages, double ratio) {
        if (messages.isEmpty()) {
            return new ArrayList<>();
        }

        List<ConversationMessage> sampled = new ArrayList<>();
        int step = (int) Math.ceil(1.0 / ratio);

        for (int i = 0; i < messages.size(); i += step) {
            sampled.add(messages.get(i));
        }

        return sampled;
    }

    /**
     * 摘要长期历史
     */
    private String summarizeLongTermHistory(List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }

        // 简单摘要：统计消息数量和时间范围
        LocalDateTime earliest = messages.get(0).getTimestamp();
        LocalDateTime latest = messages.get(messages.size() - 1).getTimestamp();

        return String.format("共 %d 条历史消息，时间范围：%s 至 %s",
                messages.size(),
                earliest.toLocalDate(),
                latest.toLocalDate());
    }

    /**
     * 估算文本的 token 数量
     * 简化算法：中文按字符数，英文按单词数 * 1.3
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int chineseChars = 0;
        int englishWords = 0;
        boolean inWord = false;

        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FA5) {
                chineseChars++;
            } else if (Character.isLetterOrDigit(c)) {
                if (!inWord) {
                    englishWords++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }

        return chineseChars + (int) (englishWords * 1.3);
    }
}
