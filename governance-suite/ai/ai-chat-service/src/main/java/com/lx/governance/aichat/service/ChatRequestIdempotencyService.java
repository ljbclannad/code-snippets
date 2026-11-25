package com.lx.governance.aichat.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.ChatRequest;
import com.lx.governance.aichat.dto.ChatResponse;
import com.lx.governance.aichat.dto.StreamEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRequestIdempotencyService {

    private static final String RESPONSE_KEY_PREFIX = "ai:chat:idempotency:v2:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, CompletableFuture<ChatResponse>> inFlightResponses = new ConcurrentHashMap<>();

    private final Map<String, Flux<StreamEvent>> streamResponses = new ConcurrentHashMap<>();

    @Value("${ai-chat.idempotency.response-ttl-minutes:10}")
    private long responseTtlMinutes = 10;

    /**
     * 同一实例内共享流式执行，断连后继续完成结算，重试从 START 回放。
     * 成功事件同时写入 Redis，缓存 TTL 内保留本地回放以容忍 Redis 写入失败。
     */
    public Flux<StreamEvent> executeStreamOrReplay(ChatRequest request, Supplier<Flux<StreamEvent>> supplier) {
        return Flux.defer(() -> {
            String requestId = normalizeRequestId(request);
            if (requestId == null) {
                return supplier.get();
            }
            String key = "stream:" + buildCacheKey(request.getConversationId(), requestId);
            return streamResponses.computeIfAbsent(key, ignored -> createSharedStream(key, supplier));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<StreamEvent> createSharedStream(String key, Supplier<Flux<StreamEvent>> supplier) {
        AtomicReference<Flux<StreamEvent>> shared = new AtomicReference<>();
        Flux<StreamEvent> source = Flux.defer(() -> {
            List<StreamEvent> events = new ArrayList<>();
            return readStreamCache(key).switchIfEmpty(Flux.defer(supplier))
                    .doOnNext(events::add)
                    .doOnComplete(() -> {
                        boolean completed = events.stream().anyMatch(event -> event.getType() == StreamEvent.EventType.DONE)
                                && events.stream().noneMatch(event -> event.getType() == StreamEvent.EventType.ERROR);
                        if (completed) {
                            try {
                                redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(events),
                                        Duration.ofMinutes(responseTtlMinutes));
                            } catch (Exception ex) {
                                log.warn("写入流式请求缓存失败，保留本地回放", ex);
                            }
                            Schedulers.parallel().schedule(() -> streamResponses.remove(key, shared.get()),
                                    responseTtlMinutes, TimeUnit.MINUTES);
                        } else {
                            streamResponses.remove(key, shared.get());
                        }
                    })
                    .doOnError(ex -> streamResponses.remove(key, shared.get()));
        });
        // cache 保持唯一上游订阅，单个客户端取消不会中断其他客户端或重复启动模型。
        shared.set(source.cache());
        return shared.get();
    }

    private Flux<StreamEvent> readStreamCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null && !json.isBlank()) {
                List<StreamEvent> events = objectMapper.readValue(json, new TypeReference<List<StreamEvent>>() {});
                return Flux.fromIterable(events);
            }
        } catch (Exception ex) {
            log.warn("读取流式请求缓存失败", ex);
        }
        return Flux.empty();
    }

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
        return RESPONSE_KEY_PREFIX + conversationId.length() + ":" + conversationId + ":" + requestId;
    }
}
