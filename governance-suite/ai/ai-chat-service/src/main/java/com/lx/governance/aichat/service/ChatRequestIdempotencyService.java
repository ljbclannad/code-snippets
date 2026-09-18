package com.lx.governance.aichat.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRequestIdempotencyService {

    private static final String RESPONSE_KEY_PREFIX = "ai:chat:idempotency:response:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, CompletableFuture<ChatResponse>> inFlightResponses = new ConcurrentHashMap<>();

    @Value("${ai-chat.idempotency.response-ttl-minutes:10}")
    private long responseTtlMinutes;

    public CompletableFuture<ChatResponse> executeOrReplay(
            String conversationId,
            ChatRequest request,
            Supplier<CompletableFuture<ChatResponse>> supplier) {
        String requestId = normalizeRequestId(request);
        if (requestId == null) {
            return supplier.get();
        }

        Optional<ChatResponse> cached = getCompletedResponse(conversationId, requestId);
        if (cached.isPresent()) {
            log.info("命中已完成请求幂等缓存 - conversationId: {}, requestId: {}",
                    conversationId, requestId);
            return CompletableFuture.completedFuture(cached.get());
        }

        String inFlightKey = buildCacheKey(conversationId, requestId);
        // 先登记占位结果，再启动任务，避免立即完成的回调递归修改 ConcurrentHashMap。
        CompletableFuture<ChatResponse> result = new CompletableFuture<>();
        CompletableFuture<ChatResponse> existing = inFlightResponses.putIfAbsent(inFlightKey, result);
        if (existing != null) {
            return existing;
        }
        try {
            // 首次读缓存与登记之间，上一轮请求可能已经完成并移除在途记录。
            Optional<ChatResponse> completed = getCompletedResponse(conversationId, requestId);
            if (completed.isPresent()) {
                inFlightResponses.remove(inFlightKey, result);
                result.complete(completed.get());
                return result;
            }
            supplier.get().whenComplete((response, ex) -> {
                // 成功缓存先于在途记录释放，确保后续请求能够回放结果。
                if (ex == null && response != null && Boolean.TRUE.equals(response.getSuccess())) {
                    cacheCompletedResponse(conversationId, requestId, response);
                }
                inFlightResponses.remove(inFlightKey, result);
                if (ex == null) {
                    result.complete(response);
                } else {
                    result.completeExceptionally(ex);
                }
            });
        } catch (RuntimeException ex) {
            inFlightResponses.remove(inFlightKey, result);
            result.completeExceptionally(ex);
            throw ex;
        }
        return result;
    }

    public Optional<ChatResponse> getCompletedResponse(ChatRequest request) {
        String requestId = normalizeRequestId(request);
        if (requestId == null) {
            return Optional.empty();
        }
        return getCompletedResponse(request.getConversationId(), requestId);
    }

    public Optional<ChatResponse> getCompletedResponse(String conversationId, String requestId) {
        if (conversationId == null || conversationId.isBlank() || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(buildCacheKey(conversationId, requestId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ChatResponse.class));
        } catch (Exception e) {
            log.warn("读取请求幂等缓存失败 - conversationId: {}, requestId: {}",
                    conversationId, requestId, e);
            return Optional.empty();
        }
    }

    public boolean isInFlight(String conversationId, String requestId) {
        if (conversationId == null || conversationId.isBlank() || requestId == null || requestId.isBlank()) {
            return false;
        }
        return inFlightResponses.containsKey(buildCacheKey(conversationId, requestId));
    }

    public void cacheCompletedResponse(ChatRequest request, ChatResponse response) {
        String requestId = normalizeRequestId(request);
        if (requestId == null || response == null || request.getConversationId() == null) {
            return;
        }
        cacheCompletedResponse(request.getConversationId(), requestId, response);
    }

    private void cacheCompletedResponse(String conversationId, String requestId, ChatResponse response) {
        try {
            redisTemplate.opsForValue().set(
                    buildCacheKey(conversationId, requestId),
                    objectMapper.writeValueAsString(response),
                    java.time.Duration.ofMinutes(responseTtlMinutes));
        } catch (Exception e) {
            log.warn("写入请求幂等缓存失败 - conversationId: {}, requestId: {}",
                    conversationId, requestId, e);
        }
    }

    public String normalizeRequestId(ChatRequest request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            return null;
        }
        return request.getRequestId().trim();
    }

    private String buildCacheKey(String conversationId, String requestId) {
        return RESPONSE_KEY_PREFIX + conversationId + ":" + requestId;
    }
}
