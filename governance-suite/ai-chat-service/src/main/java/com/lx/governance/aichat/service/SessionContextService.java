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
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                log.debug("从Redis获取会话上下文: key={}", key);
                return objectMapper.readValue(json, SessionContext.class);
            }
            return null;
        } catch (Exception e) {
            log.error("从Redis读取上下文失败: key={}", key, e);
            return null;
        }
    }

    private void saveToRedis(String key, SessionContext context) {
        try {
            String json = objectMapper.writeValueAsString(context);
            redisTemplate.opsForValue().set(key, json, CONTEXT_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("保存会话上下文到Redis: key={}", key);
        } catch (Exception e) {
            log.error("保存上下文到Redis失败: key={}", key, e);
        }
    }

    private SessionContext createDefaultContext(String conversationId, String customerId, String groupId) {
        return SessionContext.builder()
            .conversationId(conversationId)
            .customerId(customerId)
            .groupId(groupId)
            .currentTopic("健康咨询")
            .conversationPhase("consulting")
            .mentionedEntities(new ArrayList<>())
            .extractedData(new HashMap<>())
            .userIntent("一般咨询")
            .pendingQuestions(new ArrayList<>())
            .complexity("simple")
            .recentSummaries(new ArrayList<>())
            .sessionStartTime(System.currentTimeMillis())
            .messageCount(1)
            .build();
    }

    private SessionContext getContextFallback(String conversationId,
                                             String customerId,
                                             String groupId,
                                             String currentMessage,
                                             Throwable t) {
        log.warn("会话上下文服务降级: conversationId={}", conversationId);
        return createDefaultContext(conversationId, customerId, groupId);
    }

    /**
     * 更新会话上下文
     */
    public void updateContext(String conversationId, String userMessage, String aiResponse) {
        String key = CONTEXT_KEY_PREFIX + conversationId;

        try {
            SessionContext context = getFromRedis(key);
            if (context == null) {
                log.warn("上下文不存在，跳过更新: conversationId={}", conversationId);
                return;
            }

            // 1. 增加消息计数
            context.setMessageCount(context.getMessageCount() + 1);

            // 2. 添加消息摘要到滑动窗口
            String summary = truncateMessage(userMessage, MAX_SUMMARY_LENGTH);
            MessageSummary messageSummary = MessageSummary.builder()
                .userMessage(summary)
                .timestamp(System.currentTimeMillis())
                .build();

            List<MessageSummary> summaries = context.getRecentSummaries();
            summaries.add(messageSummary);

            // 3. 维护滑动窗口大小
            int windowSize = "complex".equals(context.getComplexity())
                ? COMPLEX_WINDOW_SIZE
                : SIMPLE_WINDOW_SIZE;

            if (summaries.size() > windowSize) {
                summaries.remove(0);
            }

            // 4. 调用DeepSeek更新意图分析
            SessionContext updatedContext = intentAnalyzer.analyzeIntent(userMessage, summaries);
            if (updatedContext != null) {
                context.setCurrentTopic(updatedContext.getCurrentTopic());
                context.setConversationPhase(updatedContext.getConversationPhase());
                context.setMentionedEntities(updatedContext.getMentionedEntities());
                context.setExtractedData(updatedContext.getExtractedData());
                context.setUserIntent(updatedContext.getUserIntent());
                context.setPendingQuestions(updatedContext.getPendingQuestions());
                context.setComplexity(updatedContext.getComplexity());
            }

            // 5. 更新时间戳
            context.setLastUpdateTime(System.currentTimeMillis());

            // 6. 保存到Redis并刷新TTL
            saveToRedis(key, context);

            log.debug("更新会话上下文成功: conversationId={}", conversationId);

        } catch (Exception e) {
            log.error("更新会话上下文失败: conversationId={}", conversationId, e);
        }
    }

    /**
     * 截断消息到指定长度
     */
    private String truncateMessage(String message, int maxLength) {
        if (message == null) {
            return "";
        }
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength) + "...";
    }
}
