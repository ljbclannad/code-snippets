package com.lx.governance.aichat.degradation;

import com.lx.governance.aichat.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 降级策略执行器
 * 实现各个降级级别的具体策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DegradationStrategyExecutor {

    private final DegradationManager degradationManager;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_RESPONSE_PREFIX = "degradation:cache:";
    private static final long CACHE_TTL_HOURS = 24;

    /**
     * 获取降级后的会话历史轮数
     */
    public int getConversationHistoryRounds() {
        DegradationLevel level = degradationManager.getCurrentLevel();

        switch (level) {
            case NORMAL:
            case LIGHT:
            case MEDIUM:
                return 5; // 完整历史
            case HEAVY:
                return 2; // 仅最近2轮
            case CRITICAL:
            case CIRCUIT_BREAK:
                return 0; // 无历史
            default:
                return 5;
        }
    }

    /**
     * 获取降级后的记忆检索数量
     */
    public int getMemoryRetrievalCount() {
        DegradationLevel level = degradationManager.getCurrentLevel();

        switch (level) {
            case NORMAL:
                return 10; // 完整检索
            case LIGHT:
                return 5;  // 减半
            case MEDIUM:
            case HEAVY:
            case CRITICAL:
            case CIRCUIT_BREAK:
                return 0;  // 不检索
            default:
                return 10;
        }
    }

    /**
     * 是否应该使用缓存响应
     */
    public boolean shouldUseCachedResponse() {
        DegradationLevel level = degradationManager.getCurrentLevel();
        return level == DegradationLevel.CRITICAL || level == DegradationLevel.CIRCUIT_BREAK;
    }

    /**
     * 尝试从缓存获取响应
     */
    public ChatResponse getCachedResponse(String customerId, String message) {
        if (!shouldUseCachedResponse()) {
            return null;
        }

        try {
            String cacheKey = CACHE_RESPONSE_PREFIX + customerId + ":" + hashMessage(message);
            Object cached = redisTemplate.opsForValue().get(cacheKey);

            if (cached instanceof ChatResponse) {
                log.info("使用缓存响应 - customerId: {}, level: {}",
                        customerId, degradationManager.getCurrentLevel());
                return (ChatResponse) cached;
            }
        } catch (Exception e) {
            log.error("获取缓存响应失败", e);
        }

        return null;
    }

    /**
     * 缓存响应
     */
    public void cacheResponse(String customerId, String message, ChatResponse response) {
        try {
            String cacheKey = CACHE_RESPONSE_PREFIX + customerId + ":" + hashMessage(message);
            redisTemplate.opsForValue().set(cacheKey, response, CACHE_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("缓存响应失败", e);
        }
    }

    /**
     * 获取降级提示响应（熔断时使用）
     */
    public ChatResponse getCircuitBreakResponse(String conversationId) {
        return ChatResponse.builder()
                .success(true)
                .message("抱歉，当前服务负载较高，请稍后再试。我们正在努力恢复服务。")
                .conversationId(conversationId)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 检查是否应该调用 AI API
     */
    public boolean shouldCallAiApi() {
        return degradationManager.isFeatureEnabled("aiApi");
    }

    /**
     * 获取降级提示信息（附加到响应中）
     */
    public String getDegradationNotice() {
        DegradationLevel level = degradationManager.getCurrentLevel();

        if (!level.isDegraded()) {
            return null;
        }

        switch (level) {
            case LIGHT:
                return "\n\n[系统提示: 当前服务负载较高，部分功能已临时关闭]";
            case MEDIUM:
                return "\n\n[系统提示: 当前服务处于中度降级，响应可能不够完整]";
            case HEAVY:
                return "\n\n[系统提示: 当前服务处于重度降级，仅提供基础功能]";
            case CRITICAL:
                return "\n\n[系统提示: 当前服务处于极限模式，可能使用缓存响应]";
            default:
                return null;
        }
    }

    /**
     * 简单的消息哈希（用于缓存 key）
     */
    private String hashMessage(String message) {
        // 简化实现：取前50个字符 + hashCode
        String prefix = message.length() > 50 ? message.substring(0, 50) : message;
        return prefix.hashCode() + "";
    }
}
