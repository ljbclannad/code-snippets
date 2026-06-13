package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.MessageSummary;
import com.lx.governance.aichat.dto.SessionContext;
import com.lx.governance.aichat.dto.TaskTriggerDecision;

import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;

/**
 * 上下文处理服务
 */
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

    @Retry(name = "sessionContext", fallbackMethod = "getContextFallback")
    public SessionContext getOrCreateContext(String conversationId,
            String customerId,
            String groupId,
            String currentMessage) {
        String key = CONTEXT_KEY_PREFIX + conversationId;

        try {
            SessionContext context = getFromRedis(key);

            if (context == null) {
                // 首次创建：调用DeepSeek分析意图
                context = intentAnalyzer.analyzeIntentWithTimeout(
                        currentMessage,
                        Collections.emptyList(),
                        5000);

                if (context == null) {
                    context = createDefaultContext(conversationId, customerId, groupId);
                }

                context.setConversationId(conversationId);
                context.setCustomerId(customerId);
                context.setGroupId(groupId);
                context.setSessionStartTime(System.currentTimeMillis());
                context.setMessageCount(1);

                saveToRedis(key, context);
            } else {
                // 已有上下文：检测是否需要重新分析
                if (shouldReanalyzeIntent(context, currentMessage)) {
                    log.info("检测到需要重新分析意图: conversationId={}, 当前话题={}, 消息数={}",
                            conversationId, context.getCurrentTopic(), context.getMessageCount());

                    List<MessageSummary> recentMessages = context.getRecentSummaries();
                    SessionContext updatedContext = intentAnalyzer.analyzeIntentWithTimeout(
                            currentMessage,
                            recentMessages,
                            5000);

                    if (updatedContext != null) {
                        // 合并更新（保留元数据）
                        mergeContextUpdate(context, updatedContext);
                        saveToRedis(key, context);

                        log.info("意图分析已更新: conversationId={}, 新话题={}, 新意图={}",
                                conversationId, context.getCurrentTopic(), context.getUserIntent());
                    }
                }
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
     * 记录最近一次任务触发判定，便于后续复盘和观测。
     */
    public void recordTaskTriggerDecision(String conversationId, TaskTriggerDecision decision) {
        String key = CONTEXT_KEY_PREFIX + conversationId;

        try {
            SessionContext context = getFromRedis(key);
            if (context == null) {
                log.warn("上下文不存在，跳过记录任务触发判定: conversationId={}", conversationId);
                return;
            }
            if (decision == null) {
                log.warn("任务触发判定为空，跳过记录: conversationId={}", conversationId);
                return;
            }

            context.setLastTaskTriggerRoute(decision.getRoute());
            context.setLastTaskType(decision.getTaskType());
            context.setLastTaskTriggerReasons(new ArrayList<>(decision.getReasons()));
            context.setLastTaskTriggerSummary(decision.getSummary());
            context.setLastTaskTriggerAt(System.currentTimeMillis());
            context.setLastUpdateTime(System.currentTimeMillis());

            saveToRedis(key, context);
            log.debug("记录任务触发判定成功: conversationId={}, route={}, taskType={}",
                conversationId, decision.getRoute(), decision.getTaskType());
        } catch (Exception e) {
            log.error("记录任务触发判定失败: conversationId={}", conversationId, e);
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

    /**
     * 判断是否需要重新分析意图（混合策略）
     */
    private boolean shouldReanalyzeIntent(SessionContext context, String currentMessage) {
        // 策略1：首条消息后的第2条，快速验证首次分析
        if (context.getMessageCount() == 1) {
            log.debug("触发重新分析: 第2条消息验证");
            return true;
        }

        // 策略2：关键词检测（成本低，优先检查）
        if (detectTopicChange(context.getCurrentTopic(), currentMessage)) {
            log.debug("触发重新分析: 检测到话题切换关键词");
            return true;
        }

        // 策略3：定期重新分析（每5条）
        if (context.getMessageCount() % 5 == 0) {
            log.debug("触发重新分析: 定期检查（每5条消息）");
            return true;
        }

        // 策略4：长时间未更新（可能是新会话）
        long timeSinceLastUpdate = System.currentTimeMillis() - context.getLastUpdateTime();
        if (timeSinceLastUpdate > 10 * 60 * 1000) {
            log.debug("触发重新分析: 长时间未更新（{}分钟）", timeSinceLastUpdate / 60000);
            return true;
        }

        return false;
    }

    /**
     * 简单的话题切换检测
     */
    private boolean detectTopicChange(String currentTopic, String message) {
        if (currentTopic == null || message == null) {
            return false;
        }

        // 定义话题关键词映射
        Map<String, List<String>> topicKeywords = new HashMap<>();
        topicKeywords.put("血糖管理", List.of("血糖", "糖尿病", "胰岛素", "血糖仪", "降糖"));
        topicKeywords.put("饮食咨询", List.of("饮食", "吃", "食物", "营养", "菜谱", "食谱", "餐"));
        topicKeywords.put("减肥", List.of("减肥", "体重", "瘦身", "减重", "肥胖", "瘦"));
        topicKeywords.put("血压管理", List.of("血压", "高血压", "低血压", "血压计"));
        topicKeywords.put("运动健康", List.of("运动", "锻炼", "健身", "跑步", "散步", "走路"));
        topicKeywords.put("用药咨询", List.of("药", "吃药", "服药", "用药", "药物", "处方"));
        topicKeywords.put("睡眠健康", List.of("睡眠", "失眠", "睡觉", "休息", "困"));

        String messageLower = message.toLowerCase();

        // 检查消息是否包含其他话题的关键词
        for (Map.Entry<String, List<String>> entry : topicKeywords.entrySet()) {
            String topic = entry.getKey();

            // 跳过当前话题
            if (topic.equals(currentTopic)) {
                continue;
            }

            // 检查是否匹配其他话题的关键词
            for (String keyword : entry.getValue()) {
                if (messageLower.contains(keyword)) {
                    log.debug("检测到话题切换信号: {} -> {}, 关键词: {}",
                            currentTopic, topic, keyword);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 合并上下文更新
     */
    private void mergeContextUpdate(SessionContext context, SessionContext updatedContext) {
        context.setCurrentTopic(updatedContext.getCurrentTopic());
        context.setConversationPhase(updatedContext.getConversationPhase());
        context.setMentionedEntities(updatedContext.getMentionedEntities());
        context.setExtractedData(updatedContext.getExtractedData());
        context.setUserIntent(updatedContext.getUserIntent());
        context.setPendingQuestions(updatedContext.getPendingQuestions());
        context.setComplexity(updatedContext.getComplexity());
        context.setLastUpdateTime(System.currentTimeMillis());
    }
}
