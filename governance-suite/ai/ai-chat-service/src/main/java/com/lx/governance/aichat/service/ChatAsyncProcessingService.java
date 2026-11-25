package com.lx.governance.aichat.service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatGenerationResult;
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
    private final VisitorIdentityService visitorIdentityService;

    @Value("${ai-chat.chat-history.sync-enabled:true}")
    private boolean syncEnabled;

    public ChatAsyncProcessingService(
            AiChatHistoryMapper chatHistoryMapper,
            RabbitTemplate rabbitTemplate,
            MemoryService memoryService,
            @Qualifier("memoryExtractExecutor") Executor memoryExtractExecutor,
            VisitorIdentityService visitorIdentityService) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.memoryService = memoryService;
        this.memoryExtractExecutor = memoryExtractExecutor;
        this.visitorIdentityService = visitorIdentityService;
    }

    public void handleChatCompleted(ChatRequest request, ChatGenerationResult response, String correlationId) {
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    persistHistory(request, response);
                    triggerMemoryExtraction(request, response, correlationId);
                } catch (Exception e) {
                    log.error("异步处理聊天结果失败 - customerId: {}, correlationId: {}",
                            request.getCustomerId(), correlationId, e);
                }
            }, memoryExtractExecutor);
        } catch (RejectedExecutionException ex) {
            log.warn("后处理线程池已满，直接保存历史 - conversationId: {}, correlationId: {}",
                    response.getConversationId(), correlationId);
            persistHistory(request, response);
        }
    }

    private void persistHistory(ChatRequest request, ChatGenerationResult response) {
        AiChatHistory history = saveConversationHistory(request, response);
        if (history != null && syncEnabled) {
            syncToElasticsearch(history);
        }
    }

    private AiChatHistory saveConversationHistory(ChatRequest request, ChatGenerationResult response) {
        try {
            AiChatHistory history = new AiChatHistory();
            history.setCustomerId(request.getCustomerId());
            history.setConversationId(response.getConversationId());
            history.setUserMessage(request.getMessage());
            history.setAiResponse(response.getAnswer());
            if (response.getMetadata() != null) {
                var usage = response.getMetadata().resolveTokenUsage();
                history.setPromptTokens(usage.promptTokens());
                history.setCompletionTokens(usage.completionTokens());
                history.setTotalTokens(usage.totalTokens());
            }
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

    private void triggerMemoryExtraction(ChatRequest request, ChatGenerationResult response, String correlationId) {
        try {
            if (visitorIdentityService.isVisitor(request.getCustomerId())) {
                log.debug("visitor 模式跳过长期记忆提取 - customerId: {}, correlationId: {}",
                        request.getCustomerId(), correlationId);
                return;
            }
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
