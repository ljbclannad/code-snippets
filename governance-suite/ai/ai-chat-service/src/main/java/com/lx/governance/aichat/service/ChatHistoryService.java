package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.aichat.dto.ConversationHistoryMessageDTO;
import com.lx.governance.aichat.dto.ConversationHistoryResponseDTO;
import com.lx.governance.aichat.dto.ConversationMessage;
import com.lx.governance.aichat.dto.ConversationSummaryDTO;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatHistoryService {

    private final AiChatHistoryMapper chatHistoryMapper;

    public List<ConversationSummaryDTO> listConversations(String customerId, int page, int size) {
        validateCustomerId(customerId);
        validatePagination(page, size);

        List<AiChatHistory> historyRows = chatHistoryMapper.selectList(new LambdaQueryWrapper<AiChatHistory>()
                .eq(AiChatHistory::getCustomerId, customerId)
                .apply("BINARY customer_id = BINARY {0}", customerId)
                .eq(AiChatHistory::getIsDeleted, 0)
                .isNotNull(AiChatHistory::getConversationId)
                .orderByDesc(AiChatHistory::getCreateTime)
                .orderByDesc(AiChatHistory::getId));

        Map<String, ConversationSummaryDTO> grouped = new LinkedHashMap<>();
        for (AiChatHistory row : historyRows) {
            String conversationId = row.getConversationId();
            if (conversationId == null || conversationId.isBlank()) {
                continue;
            }
            grouped.compute(conversationId, (key, existing) -> {
                if (existing == null) {
                    return ConversationSummaryDTO.builder()
                            .conversationId(conversationId)
                            .customerId(row.getCustomerId())
                            .latestUserMessage(row.getUserMessage())
                            .latestAiResponse(row.getAiResponse())
                            .latestMessageTime(row.getCreateTime())
                            .rounds(1)
                            .build();
                }
                existing.setRounds(existing.getRounds() + 1);
                return existing;
            });
        }

        List<ConversationSummaryDTO> summaries = grouped.values().stream()
                .sorted(Comparator.comparing(ConversationSummaryDTO::getLatestMessageTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        int fromIndex = Math.min(page * size, summaries.size());
        int toIndex = Math.min(fromIndex + size, summaries.size());
        return summaries.subList(fromIndex, toIndex);
    }

    public ConversationHistoryResponseDTO getConversationHistory(String customerId, String conversationId) {
        validateCustomerId(customerId);
        validateConversationId(conversationId);

        List<AiChatHistory> historyRows = chatHistoryMapper.selectList(new LambdaQueryWrapper<AiChatHistory>()
                .eq(AiChatHistory::getCustomerId, customerId)
                .apply("BINARY customer_id = BINARY {0}", customerId)
                .eq(AiChatHistory::getConversationId, conversationId)
                .apply("BINARY conversation_id = BINARY {0}", conversationId)
                .eq(AiChatHistory::getIsDeleted, 0)
                .orderByAsc(AiChatHistory::getCreateTime)
                .orderByAsc(AiChatHistory::getId));

        if (historyRows.isEmpty()) {
            throw new IllegalArgumentException("Conversation not found: " + conversationId);
        }

        List<ConversationHistoryMessageDTO> messages = new ArrayList<>();
        for (AiChatHistory row : historyRows) {
            messages.add(ConversationHistoryMessageDTO.builder()
                    .role(ConversationMessage.Role.USER)
                    .content(row.getUserMessage())
                    .timestamp(row.getCreateTime())
                    .promptTokens(row.getPromptTokens())
                    .completionTokens(row.getCompletionTokens())
                    .totalTokens(row.getTotalTokens())
                    .build());

            messages.add(ConversationHistoryMessageDTO.builder()
                    .role(ConversationMessage.Role.ASSISTANT)
                    .content(row.getAiResponse())
                    .timestamp(row.getCreateTime())
                    .promptTokens(row.getPromptTokens())
                    .completionTokens(row.getCompletionTokens())
                    .totalTokens(row.getTotalTokens())
                    .build());
        }

        return ConversationHistoryResponseDTO.builder()
                .customerId(customerId)
                .conversationId(conversationId)
                .rounds(historyRows.size())
                .messages(messages)
                .build();
    }

    private void validateCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId cannot be blank");
        }
    }

    private void validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId cannot be blank");
        }
    }

    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be greater than or equal to 0");
        }
        if (size <= 0 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }
}
