package com.lx.governance.aichat.service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ChatAsyncProcessingService {

    private final AiChatHistoryMapper chatHistoryMapper;
    private final RabbitTemplate rabbitTemplate;
    private final MemoryService memoryService;
    private final Executor memoryExtractExecutor;

    @Value("${ai-chat.chat-history.sync-enabled:true}")
    private boolean syncEnabled;

    public ChatAsyncProcessingService(
            AiChatHistoryMapper chatHistoryMapper,
            RabbitTemplate rabbitTemplate,
            MemoryService memoryService,
            @Qualifier("memoryExtractExecutor") Executor memoryExtractExecutor) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.memoryService = memoryService;
        this.memoryExtractExecutor = memoryExtractExecutor;
    }

    public void handleChatCompleted(ChatRequest request, DifyResponse response, String correlationId) {
        CompletableFuture.runAsync(() -> {
            try {
                AiChatHistory history = saveConversationHistory(request, response);
                if (history != null && syncEnabled) {
                    syncToElasticsearch(history);
                }
                triggerMemoryExtraction(request, response, correlationId);
            } catch (Exception e) {
                log.error("异步处理聊天结果失败 - customerId: {}, correlationId: {}",
                        request.getCustomerId(), correlationId, e);
            }
        }, memoryExtractExecutor);
    }

    private AiChatHistory saveConversationHistory(ChatRequest request, DifyResponse response) {
        try {
            AiChatHistory history = new AiChatHistory();
            history.setCustomerId(request.getCustomerId());
            history.setConversationId(response.getConversationId());
            history.setUserMessage(request.getMessage());
            history.setAiResponse(response.getAnswer());
            history.setPromptTokens(null);
            history.setCompletionTokens(null);
            history.setTotalTokens(null);
            history.setCreateTime(LocalDateTime.now());
            history.setIsDeleted(0);

            chatHistoryMapper.insert(history);
            return history;
        } catch (Exception e) {
            log.error("保存对话历史失败 - conversationId: {}", request.getConversationId(), e);
            return null;
        }
    }

    private void syncToElasticsearch(AiChatHistory history) {
        try {
            ChatHistorySyncMessage message = ChatHistorySyncMessage.builder()
                    .id(history.getId())
                    .customerId(history.getCustomerId())
                    .conversationId(history.getConversationId())
                    .userMessage(history.getUserMessage())
                    .aiResponse(history.getAiResponse())
                    .promptTokens(history.getPromptTokens())
                    .completionTokens(history.getCompletionTokens())
                    .totalTokens(history.getTotalTokens())
                    .createTime(history.getCreateTime())
                    .build();

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CHAT_HISTORY_SYNC_EXCHANGE,
                    RabbitMQConfig.CHAT_HISTORY_SYNC_ROUTING_KEY,
                    message);
        } catch (Exception e) {
            log.error("发送对话历史同步消息失败 - id: {}", history.getId(), e);
        }
    }

    private void triggerMemoryExtraction(ChatRequest request, DifyResponse response, String correlationId) {
        try {
            memoryService.extractMemory(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getMessage(),
                    response.getAnswer());
        } catch (Exception e) {
            log.error("记忆提取触发失败 - customerId: {}, correlationId: {}",
                    request.getCustomerId(), correlationId, e);
        }
    }
}
