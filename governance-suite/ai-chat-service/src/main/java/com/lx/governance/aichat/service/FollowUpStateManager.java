package com.lx.governance.aichat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.governance.aichat.config.ProfileCompletionProperties;
import com.lx.governance.aichat.dto.FollowUpState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 追问状态管理器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowUpStateManager {

    private final RedisTemplate<String, String> redisTemplate;
    private final ProfileCompletionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String STATE_KEY_PREFIX = "ai:followup:state:";
    private static final long STATE_TTL_MINUTES = 30;

    /**
     * 判断是否应该停止追问
     *
     * @param conversationId 会话ID
     * @return true 如果应该停止追问
     */
    public boolean shouldStopFollowUp(String conversationId) {
        FollowUpState state = getState(conversationId);

        if (state == null) {
            return false; // 首次追问
        }

        // 检查1：超过最大轮数
        if (state.getCurrentRound() >= properties.getFollowUp().getMaxRounds()) {
            log.info("达到最大追问轮数，停止追问: conversationId={}, round={}",
                    conversationId, state.getCurrentRound());
            return true;
        }

        // 检查2：超时
        long elapsed = System.currentTimeMillis() - state.getFirstFollowUpTime();
        long timeoutMs = properties.getFollowUp().getTimeoutMinutes() * 60 * 1000L;
        if (elapsed > timeoutMs) {
            log.info("追问超时，停止追问: conversationId={}, elapsed={}ms",
                    conversationId, elapsed);
            return true;
        }

        // 检查3：已完成标记
        if (state.isCompleted()) {
            log.info("追问已完成: conversationId={}", conversationId);
            return true;
        }

        return false;
    }

    /**
     * 记录追问
     *
     * @param conversationId 会话ID
     * @param questions      追问问题列表
     * @param fieldPaths     字段路径列表
     */
    public void recordFollowUp(String conversationId, List<String> questions, List<String> fieldPaths) {
        FollowUpState state = getState(conversationId);

        if (state == null) {
            // 首次追问
            state = FollowUpState.builder()
                    .conversationId(conversationId)
                    .currentRound(1)
                    .askedQuestions(new ArrayList<>())
                    .askedFieldPaths(new HashSet<>())
                    .firstFollowUpTime(System.currentTimeMillis())
                    .completed(false)
                    .build();
        } else {
            // 增加轮数
            state.setCurrentRound(state.getCurrentRound() + 1);
        }

        // 记录追问的问题和字段
        state.getAskedQuestions().addAll(questions);
        if (fieldPaths != null) {
            state.getAskedFieldPaths().addAll(fieldPaths);
        }
        state.setLastFollowUpTime(System.currentTimeMillis());

        saveState(state);

        log.info("记录追问状态: conversationId={}, round={}, questions={}",
                conversationId, state.getCurrentRound(), questions.size());
    }

    /**
     * 标记追问完成
     *
     * @param conversationId 会话ID
     */
    public void markCompleted(String conversationId) {
        FollowUpState state = getState(conversationId);
        if (state != null) {
            state.setCompleted(true);
            saveState(state);
            log.info("标记追问完成: conversationId={}", conversationId);
        }
    }

    /**
     * 检查字段是否已追问过
     *
     * @param conversationId 会话ID
     * @param fieldPath      字段路径
     * @return true 如果已追问过
     */
    public boolean hasAskedField(String conversationId, String fieldPath) {
        FollowUpState state = getState(conversationId);
        if (state == null) {
            return false;
        }
        return state.getAskedFieldPaths().contains(fieldPath);
    }

    /**
     * 从 Redis 获取状态
     */
    private FollowUpState getState(String conversationId) {
        String key = STATE_KEY_PREFIX + conversationId;
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, FollowUpState.class);
            }
        } catch (Exception e) {
            log.error("获取追问状态失败: conversationId={}", conversationId, e);
        }
        return null;
    }

    /**
     * 保存状态到 Redis
     */
    private void saveState(FollowUpState state) {
        String key = STATE_KEY_PREFIX + state.getConversationId();
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, STATE_TTL_MINUTES, TimeUnit.MINUTES);
            log.debug("保存追问状态到Redis: key={}", key);
        } catch (Exception e) {
            log.error("保存追问状态失败: conversationId={}", state.getConversationId(), e);
        }
    }
}
