package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class SessionContextService {

    private static final String CONTEXT_KEY_PREFIX = "ai:session:context:";
    private static final long CONTEXT_TTL_MINUTES = 30;
    private static final int SIMPLE_WINDOW_SIZE = 3;
    private static final int COMPLEX_WINDOW_SIZE = 5;
    private static final int MAX_SUMMARY_LENGTH = 50;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DeepSeekIntentAnalyzer intentAnalyzer;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取或创建会话上下文
     */
    @CircuitBreaker(name = "sessionContext", fallbackMethod = "getContextFallback")
    @Retry(name = "sessionContext", fallbackMethod = "getContextFallback")
    public SessionContext getOrCreateContext(String conversationId,
                                            String customerId,
                                            String groupId,
                                            String currentMessage) {
        String key = CONTEXT_KEY_PREFIX + conversationId;

        try {
            SessionContext context = getFromRedis(key);

            if (context == null) {
                // 调用DeepSeek分析意图
                context = intentAnalyzer.analyzeIntentWithTimeout(
                    currentMessage,
                    Collections.emptyList(),
                    5000
                );

                if (context == null) {
                    context = createDefaultContext(conversationId, customerId, groupId);
                }

                context.setConversationId(conversationId);
                context.setCustomerId(customerId);
                context.setGroupId(groupId);
                context.setSessionStartTime(System.currentTimeMillis());
                context.setMessageCount(1);

                saveToRedis(key, context);
            }

            return context;

        } catch (Exception e) {
            log.error("获取会话上下文失败: conversationId={}", conversationId, e);
            return createDefaultContext(conversationId, customerId, groupId);
        }
    }

    // 其他方法将在下一个任务实现
    private SessionContext getFromRedis(String key) {
        // TODO: Task 4实现
        return null;
    }

    private void saveToRedis(String key, SessionContext context) {
        // TODO: Task 4实现
    }

    private SessionContext createDefaultContext(String conversationId, String customerId, String groupId) {
        // TODO: Task 4实现
        return null;
    }

    private SessionContext getContextFallback(String conversationId,
                                             String customerId,
                                             String groupId,
                                             String currentMessage,
                                             Throwable t) {
        log.warn("会话上下文服务降级: conversationId={}", conversationId);
        return createDefaultContext(conversationId, customerId, groupId);
    }
}
