package com.lx.governance.aichat.client;

import com.lx.governance.aichat.dto.ExtractionRequestMessage;
import com.lx.governance.aichat.dto.MemoryItemDTO;
import com.lx.governance.aichat.dto.MemoryRequestMessage;
import com.lx.governance.aichat.dto.MemoryResponseMessage;
import com.lx.governance.aichat.dto.StructuredMemoryQuery;
import com.lx.governance.common.trace.Trace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 向量记忆客户端 - 使用固定响应队列
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorMemoryClientFixed {

    private final RabbitTemplate rabbitTemplate;

    private static final String REQUEST_QUEUE = "vector.memory.request";
    private static final String REPLY_QUEUE = "ai.chat.vector.memory.reply";
    private static final String EXTRACTION_QUEUE = "memory.extraction.queue";
    private static final long TIMEOUT_MS = 5000;

    private final Map<String, CompletableFuture<MemoryResponseMessage>> pendingRequests = new ConcurrentHashMap<>();

    public CompletableFuture<List<MemoryItemDTO>> requestMemories(String groupId, String customerId, String query, Integer topK) {
        return requestMemories(groupId, customerId, query, topK, null, null, null, null, null);
    }

    public CompletableFuture<List<MemoryItemDTO>> requestMemories(
            String groupId,
            String customerId,
            String query,
            Integer topK,
            Set<String> memoryTypes,
            Double minConfidence,
            Boolean verifiedOnly,
            Integer recentDays,
            StructuredMemoryQuery structuredQuery) {
        String correlationId = Trace.getOrCreate();
        long startTime = System.currentTimeMillis();

        log.info("Sending memory request: groupId={}, customerId={}, query={}, topK={}, correlationId={}",
                groupId, customerId, query, topK, correlationId);

        MemoryRequestMessage request = new MemoryRequestMessage();
        request.setGroupId(groupId);
        request.setCustomerId(customerId);
        request.setQuery(query);
        request.setTopK(topK != null ? topK : 5);
        request.setCorrelationId(correlationId);
        request.setTimestamp(System.currentTimeMillis());
        request.setMemoryTypes(memoryTypes);
        request.setMinConfidence(minConfidence);
        request.setVerifiedOnly(verifiedOnly);
        request.setRecentDays(recentDays);
        request.setStructuredQuery(structuredQuery);

        CompletableFuture<MemoryResponseMessage> future = new CompletableFuture<>();
        pendingRequests.put(correlationId, future);

        rabbitTemplate.convertAndSend(REQUEST_QUEUE, request, message -> {
            message.getMessageProperties().setReplyTo(REPLY_QUEUE);
            message.getMessageProperties().setCorrelationId(correlationId);
            return message;
        });

        return future.orTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .handle((response, ex) -> {
                    pendingRequests.remove(correlationId);
                    long duration = System.currentTimeMillis() - startTime;

                    if (ex != null) {
                        log.error("Memory request timeout: groupId={}, timeout={}ms, duration={}ms",
                                groupId, TIMEOUT_MS, duration);
                        throw new RuntimeException("Memory request timeout after " + TIMEOUT_MS + "ms");
                    }

                    if (!response.getSuccess()) {
                        log.error("Memory request failed: groupId={}, error={}, duration={}ms",
                                groupId, response.getErrorMessage(), duration);
                        throw new RuntimeException("Failed to request memories: " + response.getErrorMessage());
                    }

                    log.info("Memory request succeeded: groupId={}, count={}, duration={}ms",
                            groupId, response.getData().size(), duration);
                    return response.getData();
                });
    }

    @RabbitListener(queues = REPLY_QUEUE)
    public void handleResponse(MemoryResponseMessage response, Message message) {
        String correlationId = message.getMessageProperties().getCorrelationId();
        log.info("Received memory response: correlationId={}, success={}", correlationId, response.getSuccess());

        CompletableFuture<MemoryResponseMessage> future = pendingRequests.remove(correlationId);
        if (future != null) {
            future.complete(response);
        } else {
            log.warn("No pending request found for correlationId: {}", correlationId);
        }
    }

    /**
     * 触发记忆提取（异步，不等待响应）
     */
    public void triggerMemoryExtraction(String groupId, String customerId, String userMessage, String aiResponse) {
        String correlationId = Trace.getOrCreate();

        log.info("Triggering memory extraction: groupId={}, customerId={}, correlationId={}",
                groupId, customerId, correlationId);

        ExtractionRequestMessage request = new ExtractionRequestMessage();
        request.setGroupId(groupId);
        request.setCustomerId(customerId);
        request.setUserMessage(userMessage);
        request.setAiResponse(aiResponse);
        request.setCorrelationId(correlationId);
        request.setTimestamp(System.currentTimeMillis());

        // 异步发送，不等待响应
        rabbitTemplate.convertAndSend(EXTRACTION_QUEUE, request);

        log.info("Memory extraction request sent: groupId={}, customerId={}, correlationId={}",
                groupId, customerId, correlationId);
    }
}
