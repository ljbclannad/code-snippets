package com.lx.governance.aichat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.dto.DoctorAssistantTaskResponse;
import com.lx.governance.aichat.dto.ChatRequest;
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

    private static final String DEFAULT_TOPIC = "健康咨询";
    private static final String DEFAULT_PHASE = "consulting";
    private static final String DEFAULT_INTENT = "general_health_consultation";
    private static final String DEFAULT_COMPLEXITY = "simple";
    private static final String CONTEXT_KEY_PREFIX = "ai:session:owned-context:";
    private static final long CONTEXT_TTL_MINUTES = 30;
    private static final int SIMPLE_WINDOW_SIZE = 3;
    private static final int COMPLEX_WINDOW_SIZE = 5;
    private static final int MAX_SUMMARY_LENGTH = 50;
    private static final int SHORT_FOLLOW_UP_LENGTH = 20;
    private static final List<String> FOLLOW_UP_PREFIXES = List.of("那", "还是", "仍然", "依旧", "接下来", "之前", "这次");

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private DeepSeekIntentAnalyzer intentAnalyzer;

    // 有界分段锁保护同一实例内的上下文读取、校验和写入，避免丢失并发更新。
    private static final Object[] CONTEXT_LOCKS = IntStream.range(0, 256).mapToObj(i -> new Object()).toArray();

    private Object contextLock(String conversationId) {
        return CONTEXT_LOCKS[Math.floorMod(conversationId.hashCode(), CONTEXT_LOCKS.length)];
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取或创建会话上下文
     */

    @Retry(name = "sessionContext", fallbackMethod = "getContextFallback")
    public SessionContext getOrCreateContext(String conversationId,
            String customerId,
            String groupId,
            String currentMessage) {
        return getOrCreateContext(conversationId, customerId, groupId, currentMessage, null, null);
    }

    @Retry(name = "sessionContext", fallbackMethod = "getContextFallbackWithRequest")
    public SessionContext getOrCreateContext(String conversationId,
            String customerId,
            String groupId,
            String currentMessage,
            String requestId,
            Long messageTimestamp) {
        synchronized (contextLock(conversationId)) {
            String key = CONTEXT_KEY_PREFIX + conversationId;
            long eventTimestamp = resolveEventTimestamp(messageTimestamp);

            try {
                SessionContext context = getFromRedis(key);

                if (context == null) {
                    SessionContext legacy = getFromRedis("ai:session:context:" + conversationId);
                    if (legacy != null && customerId != null && customerId.equals(legacy.getCustomerId())) {
                        context = legacy;
                        saveToRedis(key, context);
                    }
                }
                if (context != null && (customerId == null || !customerId.equals(context.getCustomerId()))) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话归属不匹配");
                }
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
                    context.setLastUpdateTime(System.currentTimeMillis());
                    context.setMessageCount(1);
                    context.setContextVersion(1L);
                    context.setLastMessageAt(eventTimestamp);
                    context.setLastProcessedRequestId(requestId);

                    saveToRedis(key, context);
                } else {
                    if (isStaleWrite(context, requestId, eventTimestamp)) {
                        log.info("检测到乱序上下文请求，复用当前最新上下文 - conversationId: {}, requestId: {}, eventTimestamp: {}, lastMessageAt: {}",
                                conversationId, requestId, eventTimestamp, context.getLastMessageAt());
                        return context;
                    }

                    // 已有上下文：检测是否需要重新分析
                    if (shouldReanalyzeIntent(context, currentMessage)) {
                        log.info("检测到需要重新分析意图: conversationId={}, 当前话题={}, 消息数={}",
                                conversationId, context.getCurrentTopic(), context.getMessageCount());

                        List<MessageSummary> recentMessages = context.getRecentSummaries();
                        SessionContext updatedContext = intentAnalyzer.analyzeIntentWithTimeout(
                                currentMessage,
                                recentMessages == null ? Collections.emptyList() : recentMessages,
                                5000);

                        if (updatedContext != null) {
                            // 合并更新（保留元数据）
                            mergeContextUpdate(context, updatedContext, requestId, eventTimestamp);
                            saveToRedis(key, context);

                            log.info("意图分析已更新: conversationId={}, 新话题={}, 新意图={}",
                                    conversationId, context.getCurrentTopic(), context.getUserIntent());
                        }
                    }
                }

                if (context.getLastMessageAt() == null || eventTimestamp > context.getLastMessageAt()) {
                    context.setLastMessageAt(eventTimestamp);
                    context.setLastProcessedRequestId(requestId);
                    context.setContextVersion(nextVersion(context.getContextVersion()));
                    saveToRedis(key, context);
                }
                return context;

            } catch (ResponseStatusException ex) {
                throw ex;
            } catch (Exception e) {
                log.error("获取会话上下文失败: conversationId={}", conversationId, e);
                return createDefaultContext(conversationId, customerId, groupId);
            }
        }
    }

    /** 随访固定回复不经过意图模型；先创建上下文，再按首次接收顺序保存患者摘要。 */
    public void recordFollowUpTurn(ChatRequest request, long receivedAt, Long taskId, String taskStatus) {
        String conversationId = request.getConversationId();
        synchronized (contextLock(conversationId)) {
            String key = CONTEXT_KEY_PREFIX + conversationId;
            SessionContext context = getFromRedis(key);
            if (context == null) {
                context = getFromRedis("ai:session:context:" + conversationId);
            }
            if (context != null && !Objects.equals(request.getCustomerId(), context.getCustomerId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "会话归属不匹配");
            }
            if (context == null) {
                context = createDefaultContext(conversationId, request.getCustomerId(), request.getGroupId());
            }
            if (isStaleWrite(context, request.getRequestId(), receivedAt)) {
                return;
            }
            boolean sameRequest = request.getRequestId() != null && !request.getRequestId().isBlank()
                    && request.getRequestId().equals(context.getLastProcessedRequestId());
            boolean sameTurn = sameRequest || Objects.equals(context.getLastMessageAt(), receivedAt)
                    && Objects.equals(context.getLastProcessedRequestId(), request.getRequestId());
            if (!sameTurn) {
                List<MessageSummary> summaries = context.getRecentSummaries();
                if (summaries == null) {
                    summaries = new ArrayList<>();
                    context.setRecentSummaries(summaries);
                }
                summaries.add(MessageSummary.builder().userMessage(truncateMessage(request.getMessage(), MAX_SUMMARY_LENGTH))
                        .timestamp(receivedAt).build());
                int windowSize = "complex".equals(context.getComplexity()) ? COMPLEX_WINDOW_SIZE : SIMPLE_WINDOW_SIZE;
                summaries.subList(0, Math.max(0, summaries.size() - windowSize)).clear();
                context.setMessageCount((context.getMessageCount() == null ? 1 : context.getMessageCount()) + 1);
            }
            if (taskId != null) {
                if (context.getExtractedData() == null) {
                    context.setExtractedData(new HashMap<>());
                }
                context.getExtractedData().put("followUpTaskId", taskId);
                context.getExtractedData().put("followUpTaskStatus", taskStatus);
            }
            context.setLastMessageAt(receivedAt);
            context.setLastProcessedRequestId(request.getRequestId());
            context.setLastUpdateTime(System.currentTimeMillis());
            context.setContextVersion(nextVersion(context.getContextVersion()));
            saveToRedis(key, context);
        }
    }

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
                .currentTopic(DEFAULT_TOPIC)
                .conversationPhase(DEFAULT_PHASE)
                .mentionedEntities(new ArrayList<>())
                .extractedData(new HashMap<>())
                .userIntent(DEFAULT_INTENT)
                .pendingQuestions(new ArrayList<>())
                .complexity(DEFAULT_COMPLEXITY)
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
        if (t instanceof ResponseStatusException ex) {
            throw ex;
        }
        log.warn("会话上下文服务降级: conversationId={}", conversationId);
        return createDefaultContext(conversationId, customerId, groupId);
    }

    private SessionContext getContextFallbackWithRequest(String conversationId,
            String customerId,
            String groupId,
            String currentMessage,
            String requestId,
            Long messageTimestamp,
            Throwable t) {
        if (t instanceof ResponseStatusException ex) {
            throw ex;
        }
        log.warn("会话上下文服务降级: conversationId={}, requestId={}", conversationId, requestId);
        return createDefaultContext(conversationId, customerId, groupId);
    }

    /**
     * 更新会话上下文
     */
    public void updateContext(String conversationId, String userMessage, String aiResponse) {
        updateContext(conversationId, userMessage, aiResponse, null, null);
    }

    public void updateContext(
            String conversationId,
            String userMessage,
            String aiResponse,
            String requestId,
            Long messageTimestamp) {
        synchronized (contextLock(conversationId)) {
            String key = CONTEXT_KEY_PREFIX + conversationId;
            long eventTimestamp = resolveEventTimestamp(messageTimestamp);

            try {
                SessionContext context = getFromRedis(key);
                if (context == null) {
                    log.warn("上下文不存在，跳过更新: conversationId={}", conversationId);
                    return;
                }

                if ((messageTimestamp == null && requestId != null
                        && !requestId.equals(context.getLastProcessedRequestId()))
                        || isStaleWrite(context, requestId, eventTimestamp)) {
                    log.info("跳过乱序上下文更新 - conversationId: {}, requestId: {}, eventTimestamp: {}, lastMessageAt: {}",
                            conversationId, requestId, eventTimestamp, context.getLastMessageAt());
                    return;
                }

                // 1. 增加消息计数
                int messageCount = context.getMessageCount() != null ? context.getMessageCount() : 1;
                context.setMessageCount(messageCount + 1);

                // 2. 添加消息摘要到滑动窗口
                String summary = truncateMessage(userMessage, MAX_SUMMARY_LENGTH);
                MessageSummary messageSummary = MessageSummary.builder()
                        .userMessage(summary)
                        .timestamp(System.currentTimeMillis())
                        .build();

                List<MessageSummary> summaries = context.getRecentSummaries();
                if (summaries == null) {
                    summaries = new ArrayList<>();
                    context.setRecentSummaries(summaries);
                }
                summaries.add(messageSummary);

                // 3. 维护滑动窗口大小
                int windowSize = "complex".equals(context.getComplexity())
                        ? COMPLEX_WINDOW_SIZE
                        : SIMPLE_WINDOW_SIZE;

                summaries.subList(0, Math.max(0, summaries.size() - windowSize)).clear();

                // 意图只在入口按需分析。写回保留入口已入库的意图及同轮更新的路由和审批状态。
                context.setLastUpdateTime(System.currentTimeMillis());
                context.setLastMessageAt(eventTimestamp);
                if (requestId != null && !requestId.isBlank()) {
                    context.setLastProcessedRequestId(requestId);
                }
                context.setContextVersion(nextVersion(context.getContextVersion()));

                saveToRedis(key, context);

                log.debug("更新会话上下文成功: conversationId={}", conversationId);

            } catch (Exception e) {
                log.error("更新会话上下文失败: conversationId={}", conversationId, e);
            }
        }
    }

    /**
     * 记录最近一次任务触发判定，便于后续复盘和观测。
     */
    public void recordTaskTriggerDecision(String conversationId, TaskTriggerDecision decision) {
        recordTaskTriggerDecision(conversationId, decision, null, null);
    }

    public void recordTaskTriggerDecision(
            String conversationId,
            TaskTriggerDecision decision,
            String requestId,
            Long messageTimestamp) {
        synchronized (contextLock(conversationId)) {
            String key = CONTEXT_KEY_PREFIX + conversationId;
            long eventTimestamp = resolveEventTimestamp(messageTimestamp);

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
                if ((messageTimestamp == null && requestId != null
                        && !requestId.equals(context.getLastProcessedRequestId()))
                        || isStaleWrite(context, requestId, eventTimestamp)) {
                    log.info("跳过乱序任务触发判定记录 - conversationId: {}, requestId: {}",
                            conversationId, requestId);
                    return;
                }

                context.setLastTaskTriggerRoute(decision.getRoute());
                context.setLastTaskType(decision.getTaskType());
                context.setLastTaskTriggerReasons(new ArrayList<>(decision.getReasons()));
                context.setLastTaskTriggerSummary(decision.getSummary());
                context.setLastTaskTriggerAt(System.currentTimeMillis());
                context.setLastUpdateTime(System.currentTimeMillis());
                context.setLastMessageAt(eventTimestamp);
                if (requestId != null && !requestId.isBlank()) {
                    context.setLastProcessedRequestId(requestId);
                }
                context.setContextVersion(nextVersion(context.getContextVersion()));

                saveToRedis(key, context);
                log.debug("记录任务触发判定成功: conversationId={}, route={}, taskType={}",
                    conversationId, decision.getRoute(), decision.getTaskType());
            } catch (Exception e) {
                log.error("记录任务触发判定失败: conversationId={}", conversationId, e);
            }
        }
    }

    public void recordAgentOutcome(String conversationId, DoctorAssistantTaskResponse response) {
        recordAgentOutcome(conversationId, response, null, null);
    }

    public void recordAgentOutcome(
            String conversationId,
            DoctorAssistantTaskResponse response,
            String requestId,
            Long messageTimestamp) {
        synchronized (contextLock(conversationId)) {
            String key = CONTEXT_KEY_PREFIX + conversationId;
            long eventTimestamp = resolveEventTimestamp(messageTimestamp);

            try {
                SessionContext context = getFromRedis(key);
                if (context == null || response == null) {
                    return;
                }
                if ((messageTimestamp == null && requestId != null
                        && !requestId.equals(context.getLastProcessedRequestId()))
                        || isStaleWrite(context, requestId, eventTimestamp)) {
                    log.info("跳过乱序 agent 执行结果记录 - conversationId: {}, requestId: {}, eventTimestamp: {}, lastMessageAt: {}",
                            conversationId, requestId, eventTimestamp, context.getLastMessageAt());
                    return;
                }

                context.setLastAgentReviewStatus(response.getStatus() != null ? response.getStatus().name() : null);
                context.setLastAgentPendingTaskId(
                        response.getPendingTask() != null ? response.getPendingTask().getPendingTaskId() : null);
                context.setLastAgentExecutionPath(response.getExecutionPath());
                context.setLastAgentOutcomeAt(System.currentTimeMillis());
                context.setLastUpdateTime(System.currentTimeMillis());
                context.setLastMessageAt(eventTimestamp);
                if (requestId != null && !requestId.isBlank()) {
                    context.setLastProcessedRequestId(requestId);
                }
                context.setContextVersion(nextVersion(context.getContextVersion()));

                saveToRedis(key, context);
                log.debug("记录 agent 执行结果成功: conversationId={}, status={}, pendingTaskId={}",
                        conversationId, context.getLastAgentReviewStatus(), context.getLastAgentPendingTaskId());
            } catch (Exception e) {
                log.error("记录 agent 执行结果失败: conversationId={}", conversationId, e);
            }
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
        Integer messageCount = context.getMessageCount();
        // 已创建上下文但尚无完成的回复，或者旧数据缺少计数。
        if (messageCount == null || messageCount <= 1) {
            return true;
        }

        // 非通用意图或待确认问题会直接影响路由，下一轮应按当前消息重新判断。
        if (!DEFAULT_INTENT.equals(context.getUserIntent())
                || (context.getPendingQuestions() != null && !context.getPendingQuestions().isEmpty())
                || isContextDependentFollowUp(context, currentMessage)) {
            return true;
        }

        // 策略2：关键词检测（成本低，优先检查）
        if (detectTopicChange(context.getCurrentTopic(), currentMessage)) {
            log.debug("触发重新分析: 检测到话题切换关键词");
            return true;
        }

        // 策略3：定期重新分析（每5条）
        if (messageCount % 5 == 0) {
            log.debug("触发重新分析: 定期检查（每5条消息）");
            return true;
        }

        // 策略4：长时间未更新（可能是新会话）
        long lastUpdateTime = context.getLastUpdateTime() != null ? context.getLastUpdateTime() : 0L;
        long timeSinceLastUpdate = System.currentTimeMillis() - lastUpdateTime;
        if (timeSinceLastUpdate > 10 * 60 * 1000) {
            log.debug("触发重新分析: 长时间未更新（{}分钟）", timeSinceLastUpdate / 60000);
            return true;
        }

        return false;
    }

    private boolean isContextDependentFollowUp(SessionContext context, String currentMessage) {
        if (currentMessage == null || context.getRecentSummaries() == null || context.getRecentSummaries().isEmpty()) {
            return false;
        }
        String message = currentMessage.strip();
        return message.length() <= SHORT_FOLLOW_UP_LENGTH && FOLLOW_UP_PREFIXES.stream().anyMatch(message::startsWith);
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
    private void mergeContextUpdate(SessionContext context, SessionContext updatedContext, String requestId, long eventTimestamp) {
        context.setCurrentTopic(updatedContext.getCurrentTopic());
        context.setConversationPhase(updatedContext.getConversationPhase());
        context.setMentionedEntities(updatedContext.getMentionedEntities());
        context.setExtractedData(updatedContext.getExtractedData());
        context.setUserIntent(updatedContext.getUserIntent());
        context.setPendingQuestions(updatedContext.getPendingQuestions());
        context.setComplexity(updatedContext.getComplexity());
        context.setLastUpdateTime(System.currentTimeMillis());
        context.setLastMessageAt(eventTimestamp);
        if (requestId != null && !requestId.isBlank()) {
            context.setLastProcessedRequestId(requestId);
        }
        context.setContextVersion(nextVersion(context.getContextVersion()));
    }

    private long resolveEventTimestamp(Long messageTimestamp) {
        return messageTimestamp != null && messageTimestamp > 0 ? messageTimestamp : System.currentTimeMillis();
    }

    private boolean isStaleWrite(SessionContext context, String requestId, long eventTimestamp) {
        if (context == null || context.getLastMessageAt() == null) {
            return false;
        }
        return eventTimestamp < context.getLastMessageAt();
    }

    private long nextVersion(Long currentVersion) {
        return currentVersion == null || currentVersion < 1 ? 1L : currentVersion + 1;
    }
}
