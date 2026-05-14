package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.*;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.UserProfileDTO;
import com.lx.governance.aichat.entity.AiChatHistory;
import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private final UserProfileClientFixed userProfileClient;
    private final MemoryService memoryService;
    private final DeepSeekClient deepSeekClient;
    private final AiChatHistoryMapper chatHistoryMapper;

    public ChatResponse handleChatRequest(ChatRequest request) {
        String correlationId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();

        log.info("开始处理聊天请求 - customerId: {}, groupId: {}, conversationId: {}, message: {}, correlationId: {}",
                request.getCustomerId(),
                request.getGroupId(),
                request.getConversationId(),
                truncate(request.getMessage(), 50),
                correlationId);

        try {
            // 1. 并行获取用户画像和记忆
            CompletableFuture<UserProfileDTO> profileFuture =
                userProfileClient.requestUserProfile(request.getCustomerId());

            CompletableFuture<List<MemoryItemDTO>> memoriesFuture =
                memoryService.retrieveMemories(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getMessage()
                );

            // 等待两个结果
            CompletableFuture.allOf(profileFuture, memoriesFuture).join();

            // 获取结果
            UserProfileDTO profile = profileFuture.get();
            List<MemoryItemDTO> memories = memoriesFuture.get();

            log.info("上下文获取完成 - customerId: {}, 记忆数: {}, correlationId: {}",
                    request.getCustomerId(), memories.size(), correlationId);

            // 2. 调用 DeepSeek API（内部使用 ContextBudgetManager 构建系统提示词）
            DifyResponse difyResponse = deepSeekClient.chat(request, profile, memories);

            // 3. 保存对话历史
            saveConversationHistory(request, difyResponse);

            // 4. 异步触发记忆提取
            CompletableFuture.runAsync(() -> {
                try {
                    memoryService.extractMemory(
                        request.getGroupId(),
                        request.getCustomerId(),
                        request.getMessage(),
                        difyResponse.getAnswer()
                    );
                } catch (Exception e) {
                    log.error("记忆提取触发失败 - customerId: {}, correlationId: {}",
                            request.getCustomerId(), correlationId, e);
                }
            });

            long duration = System.currentTimeMillis() - startTime;
            log.info("聊天请求处理完成 - customerId: {}, correlationId: {}, 耗时: {}ms",
                    request.getCustomerId(), correlationId, duration);

            return ChatResponse.success(difyResponse.getAnswer(), difyResponse.getConversationId());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("聊天请求处理失败 - customerId: {}, correlationId: {}, 耗时: {}ms",
                    request.getCustomerId(), correlationId, duration, e);
            return ChatResponse.error("聊天请求处理失败: " + e.getMessage());
        }
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
            history.setCreateTime(LocalDateTime.now());
            history.setIsDeleted(0);

            chatHistoryMapper.insert(history);

            log.debug("对话历史已保存 - conversationId: {}", response.getConversationId());

        } catch (Exception e) {
            log.error("保存对话历史失败 - conversationId: {}", request.getConversationId(), e);
            // 不影响主流程，只记录错误
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
