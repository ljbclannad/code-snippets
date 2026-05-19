package com.lx.governance.aichat.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.client.UserProfileClientFixed;
import com.lx.governance.aichat.config.RabbitMQConfig;
import com.lx.governance.aichat.document.ChatHistoryDocument;
import com.lx.governance.aichat.dto.ChatHistorySyncMessage;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import com.lx.governance.aichat.metrics.AiChatMetrics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private final UserProfileClientFixed userProfileClient;
    private final MemoryService memoryService;
    private final DeepSeekClient deepSeekClient;
    private final AiChatHistoryMapper chatHistoryMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ChatHistorySearchService searchService;
    private final SessionContextService sessionContextService;
    private final AiChatMetrics metrics;

    @Value("${ai-chat.chat-history.sync-enabled:true}")
    private boolean syncEnabled;

    @Value("${ai-chat.chat-history.auto-recommend-enabled:true}")
    private boolean autoRecommendEnabled;

    @Value("${ai-chat.chat-history.recommend-count:3}")
    private int recommendCount;

    public ChatResponse handleChatRequest(ChatRequest request) {
        // 记录请求
        metrics.recordChatRequest();

        // 包装整个响应时间
        return metrics.recordChatResponse(() -> {
            String correlationId = UUID.randomUUID().toString();
            long startTime = System.currentTimeMillis();

            log.info("开始处理聊天请求 - customerId: {}, groupId: {}, conversationId: {}, message: {}, correlationId: {}",
                    request.getCustomerId(),
                    request.getGroupId(),
                    request.getConversationId(),
                    truncate(request.getMessage(), 50),
                    correlationId);

            try {
                // 1. 获取或创建会话上下文（带监控）
                String sessionId = request.getConversationId() != null ? request.getConversationId()
                        : UUID.randomUUID().toString();

                SessionContext sessionContext = metrics
                        .recordContextAnalysis(() -> sessionContextService.getOrCreateContext(
                                sessionId,
                                request.getCustomerId(),
                                request.getGroupId(),
                                request.getMessage()));

                log.info("会话上下文已准备 - sessionId: {}, intent: {}, phase: {}, correlationId: {}",
                        sessionId,
                        sessionContext.getUserIntent(),
                        sessionContext.getConversationPhase(),
                        correlationId);

                // 2. 并行获取用户画像和记忆（完全异步 RPC 设计）
                //
                // 设计初衷：
                // - RPC 模式：通过 RabbitMQ 发送请求并等待响应（replyTo + correlationId）
                // - 完全异步：不创建工作线程，不阻塞工作线程，节省线程池资源
                // - 并行执行：两个 RPC 请求同时发送，总耗时 = max(请求1, 请求2)
                // - 高并发优化：避免工作线程池耗尽，支持数千并发请求
                //
                // 线程模型：
                // 主线程 → 发送 RabbitMQ 请求 → 注册回调 → 在 allOf().join() 阻塞
                // 监听器线程 → 接收响应 → 完成 Future → 触发回调
                //
                CompletableFuture<UserProfileDTO> profileFuture = userProfileClient
                        .requestUserProfile(request.getCustomerId())
                        .whenComplete((profile, ex) -> {
                            if (ex == null) {
                                metrics.recordProfileFetch(() -> profile);
                            }
                        });

                CompletableFuture<List<MemoryItemDTO>> memoriesFuture = memoryService.retrieveMemories(
                        request.getGroupId(),
                        request.getCustomerId(),
                        request.getMessage()).whenComplete((memories, ex) -> {
                            if (ex == null) {
                                metrics.recordMemoryRetrieval(() -> memories);
                            }
                        });

                // 等待两个结果（不阻塞工作线程，只阻塞主线程）
                CompletableFuture.allOf(profileFuture, memoriesFuture).join();

                // 获取结果
                UserProfileDTO profile = profileFuture.get();
                List<MemoryItemDTO> memories = memoriesFuture.get();

                log.info("上下文获取完成 - customerId: {}, 记忆数: {}, correlationId: {}",
                        request.getCustomerId(), memories.size(), correlationId);

                // 3. 调用 DeepSeek API（传入会话上下文，内部使用 ContextBudgetManager 构建系统提示词）
                DifyResponse difyResponse = deepSeekClient.chat(request, profile, sessionContext, memories);

                // 4. 更新会话上下文
                sessionContextService.updateContext(sessionId, request.getMessage(), difyResponse.getAnswer());
                log.debug("会话上下文已更新 - sessionId: {}, correlationId: {}", sessionId, correlationId);

                // 5. 保存对话历史
                saveConversationHistory(request, difyResponse);

                // 6. 异步触发记忆提取
                CompletableFuture.runAsync(() -> {
                    try {
                        memoryService.extractMemory(
                                request.getGroupId(),
                                request.getCustomerId(),
                                request.getMessage(),
                                difyResponse.getAnswer());
                    } catch (Exception e) {
                        log.error("记忆提取触发失败 - customerId: {}, correlationId: {}",
                                request.getCustomerId(), correlationId, e);
                    }
                });

                long duration = System.currentTimeMillis() - startTime;
                log.info("聊天请求处理完成 - customerId: {}, correlationId: {}, 耗时: {}ms",
                        request.getCustomerId(), correlationId, duration);

                // 7. 获取相似对话推荐
                if (autoRecommendEnabled) {
                    try {
                        List<ChatResponse.SimilarConversation> recommendations = getRecommendations(
                                request.getCustomerId(), request.getMessage());

                        if (!recommendations.isEmpty()) {
                            log.debug("获取到 {} 条相似对话推荐", recommendations.size());
                            return ChatResponse.successWithRecommendations(
                                    difyResponse.getAnswer(),
                                    difyResponse.getConversationId(),
                                    recommendations);
                        }
                    } catch (Exception e) {
                        log.error("获取推荐失败 - customerId: {}, correlationId: {}",
                                request.getCustomerId(), correlationId, e);
                        // 推荐失败不影响主流程，继续返回正常响应
                    }
                }

                return ChatResponse.success(difyResponse.getAnswer(), difyResponse.getConversationId());

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                log.error("聊天请求处理失败 - customerId: {}, correlationId: {}, 耗时: {}ms",
                        request.getCustomerId(), correlationId, duration, e);
                return ChatResponse.error("聊天请求处理失败: " + e.getMessage());
            }
        });
    }

    /**
     * 保存对话历史到数据库
     */
    private void saveConversationHistory(ChatRequest request, DifyResponse response) {
        try {
            // 保存一轮完整的对话（用户消息 + AI回复）
            AiChatHistory history = new AiChatHistory();
            history.setCustomerId(request.getCustomerId());
            history.setConversationId(response.getConversationId());
            history.setUserMessage(request.getMessage());
            history.setAiResponse(response.getAnswer());
            // TODO: 从 response 中提取 token 使用信息
            history.setPromptTokens(null);
            history.setCompletionTokens(null);
            history.setTotalTokens(null);
            history.setCreateTime(LocalDateTime.now());
            history.setIsDeleted(0);

            chatHistoryMapper.insert(history);

            log.debug("对话历史已保存 - conversationId: {}, id: {}", response.getConversationId(), history.getId());

            // 异步同步到 ES
            if (syncEnabled) {
                syncToElasticsearch(history);
            }

        } catch (Exception e) {
            log.error("保存对话历史失败 - conversationId: {}", request.getConversationId(), e);
            // 不影响主流程，只记录错误
        }
    }

    /**
     * 异步同步对话历史到 Elasticsearch
     */
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

            log.debug("对话历史同步消息已发送 - id: {}", history.getId());

        } catch (Exception e) {
            log.error("发送对话历史同步消息失败 - id: {}", history.getId(), e);
            // 不影响主流程，只记录错误
        }
    }

    /**
     * 获取相似对话推荐
     */
    private List<ChatResponse.SimilarConversation> getRecommendations(String customerId, String message) {
        try {
            List<ChatHistoryDocument> similarDocs = searchService.findSimilarConversations(
                    customerId, message, recommendCount);

            return similarDocs.stream()
                    .map(doc -> ChatResponse.SimilarConversation.builder()
                            .id(doc.getId())
                            .userMessage(doc.getUserMessage())
                            .aiResponse(doc.getAiResponse())
                            .createTime(doc.getCreateTime())
                            .score(null) // ES 查询可以返回相关性评分，这里暂时为 null
                            .build())
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("获取相似对话推荐失败 - customerId: {}", customerId, e);
            return List.of();
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}
