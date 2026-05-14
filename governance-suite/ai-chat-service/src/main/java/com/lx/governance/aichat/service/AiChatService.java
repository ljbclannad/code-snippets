package com.lx.governance.aichat.service;

import com.lx.governance.aichat.client.DeepSeekClient;
import com.lx.governance.aichat.client.DifyClient;
import com.lx.governance.aichat.client.UserProfileClient;
import com.lx.governance.aichat.client.VectorMemoryClient;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.DifyResponse;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.UserProfileDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

    private final UserProfileClient userProfileClient;
    private final VectorMemoryClient vectorMemoryClient;
    private final DifyClient difyClient;
    private final DeepSeekClient deepSeekClient;

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
            // 并行获取用户画像和向量记忆
            CompletableFuture<UserProfileDTO> profileFuture = CompletableFuture.supplyAsync(() ->
                userProfileClient.requestUserProfile(request.getCustomerId())
            );

            CompletableFuture<List<MemoryItemDTO>> memoriesFuture = CompletableFuture.supplyAsync(() ->
                vectorMemoryClient.requestGroupMemories(
                    request.getGroupId(),
                    request.getCustomerId(),
                    request.getMessage()
                )
            );

            // 等待两个结果
            CompletableFuture.allOf(profileFuture, memoriesFuture).join();

            // 获取结果
            UserProfileDTO profile = profileFuture.get();
            List<MemoryItemDTO> memories = memoriesFuture.get();

            log.info("上下文获取完成 - customerId: {}, correlationId: {}", request.getCustomerId(), correlationId);

            // 调用 DeepSeek API (直接调用)
            DifyResponse difyResponse = deepSeekClient.chat(request, profile, memories);

            // 调用 Dify API (已注释，保留代码)
            // DifyResponse difyResponse = difyClient.chat(request, profile, memories);

            // 异步触发记忆提取
            CompletableFuture.runAsync(() -> {
                try {
                    vectorMemoryClient.triggerMemoryExtraction(
                        request.getGroupId(),
                        request.getCustomerId(),
                        request.getMessage(),
                        difyResponse.getAnswer()
                    );
                } catch (Exception e) {
                    log.error("记忆提取触发失败 - customerId: {}, correlationId: {}", request.getCustomerId(), correlationId, e);
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
