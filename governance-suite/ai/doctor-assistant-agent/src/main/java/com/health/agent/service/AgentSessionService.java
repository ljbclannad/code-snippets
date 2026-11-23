package com.health.agent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.mapper.AgentSessionMapper;
import com.health.agent.mapper.PendingTaskMapper;
import com.health.agent.model.AgentContext;
import com.health.agent.model.AgentSession;
import com.health.agent.model.Message;
import com.health.agent.model.PendingTask;
import com.health.agent.model.SessionStatus;
import com.health.agent.model.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 会话服务
 */
@Slf4j
@Service
public class AgentSessionService {

    @Autowired
    private AgentSessionMapper sessionMapper;

    @Autowired
    private PendingTaskMapper taskMapper;

    @Autowired(required = false)
    private ContextEvidenceRetentionService contextEvidenceRetentionService;

    /**
     * 创建会话
     */
    public AgentSession createSession(String customerId, String doctorId, String goal) {
        return createSession(customerId, doctorId, goal, null);
    }

    public AgentSession createSession(String customerId, String doctorId, String goal, String conversationId) {
        AgentContext context = AgentContext.initialize(customerId, doctorId, goal);
        AgentSession session = AgentSession.builder()
            .conversationId(conversationId)
            .customerId(customerId)
            .doctorId(doctorId)
            .goal(goal)
            .status(SessionStatus.RUNNING.name())
            .historyJson("[]")
            .messagesJson("[]")
            .contextJson(JSON.toJSONString(context))
            .currentStep(0)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

        sessionMapper.insert(session);
        log.info("创建会话: sessionId={}, customerId={}, goal={}", session.getId(), customerId, goal);

        return session;
    }

    /**
     * 更新会话状态（包含消息）
     */
    public void updateSession(String sessionId, List<Step> history, List<Message> messages, int currentStep) {
        updateSession(sessionId, history, messages, AgentContext.empty(), currentStep);
    }

    public void updateSession(
        String sessionId,
        List<Step> history,
        List<Message> messages,
        AgentContext context,
        int currentStep
    ) {
        AgentSession session = getById(sessionId);
        if (session == null) {
            log.warn("会话不存在: {}", sessionId);
            return;
        }

        session.setHistoryJson(JSON.toJSONString(history));
        session.setMessagesJson(JSON.toJSONString(messages));
        if (context != null) {
            session.setContextJson(JSON.toJSONString(retainedContext(context)));
        }
        session.setCurrentStep(currentStep);
        session.setUpdatedAt(LocalDateTime.now());

        sessionMapper.updateById(session);
        log.debug("更新会话: sessionId={}, step={}", sessionId, currentStep);
    }

    /**
     * 暂停会话（等待人工确认）
     */
    public void pauseSession(String sessionId, List<Step> history, List<Message> messages) {
        pauseSession(sessionId, history, messages, AgentContext.empty());
    }

    public void pauseSession(String sessionId, List<Step> history, List<Message> messages, AgentContext context) {
        AgentSession session = getById(sessionId);
        if (session == null) {
            log.warn("会话不存在: {}", sessionId);
            return;
        }

        session.setStatus(SessionStatus.PAUSED.name());
        session.setHistoryJson(JSON.toJSONString(history));
        session.setMessagesJson(JSON.toJSONString(messages));
        if (context != null) {
            session.setContextJson(JSON.toJSONString(retainedContext(context)));
        }
        session.setUpdatedAt(LocalDateTime.now());

        sessionMapper.updateById(session);
        log.info("暂停会话: sessionId={}", sessionId);
    }

    /**
     * 恢复会话（人工确认后）
     */
    public void resumeSession(String sessionId) {
        AgentSession session = getById(sessionId);
        if (session == null) {
            log.warn("会话不存在: {}", sessionId);
            return;
        }

        session.setStatus(SessionStatus.RUNNING.name());
        session.setUpdatedAt(LocalDateTime.now());

        sessionMapper.updateById(session);
        log.info("恢复会话: sessionId={}", sessionId);
    }

    /**
     * 完成会话
     */
    public void completeSession(String sessionId, List<Step> history) {
        completeSession(sessionId, history, null);
    }

    public void completeSession(String sessionId, List<Step> history, AgentContext context) {
        AgentSession session = getById(sessionId);
        if (session == null) {
            log.warn("会话不存在: {}", sessionId);
            return;
        }

        session.setStatus(SessionStatus.COMPLETED.name());
        session.setHistoryJson(JSON.toJSONString(history));
        if (context != null) {
            session.setContextJson(JSON.toJSONString(retainedContext(context)));
        }
        session.setCompletedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());

        sessionMapper.updateById(session);
        log.info("完成会话: sessionId={}", sessionId);
    }

    /**
     * 会话失败
     */
    public void failSession(String sessionId, String errorMessage) {
        AgentSession session = getById(sessionId);
        if (session == null) {
            log.warn("会话不存在: {}", sessionId);
            return;
        }

        session.setStatus(SessionStatus.FAILED.name());
        session.setErrorMessage(errorMessage);
        session.setUpdatedAt(LocalDateTime.now());

        sessionMapper.updateById(session);
        log.error("会话失败: sessionId={}, error={}", sessionId, errorMessage);
    }

    /**
     * 根据ID查询会话
     */
    public AgentSession getById(String sessionId) {
        try {
            Long id = Long.parseLong(sessionId);
            return sessionMapper.selectById(id);
        } catch (NumberFormatException e) {
            log.error("无效的会话ID: {}", sessionId);
            return null;
        }
    }

    /**
     * 获取待确认任务
     */
    public PendingTask getPendingTask(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    /**
     * 查询医生的待处理会话
     */
    public List<AgentSession> listPendingByDoctor(String doctorId) {
        LambdaQueryWrapper<AgentSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentSession::getDoctorId, doctorId)
            .eq(AgentSession::getStatus, SessionStatus.PAUSED.name())
            .orderByDesc(AgentSession::getCreatedAt);

        return sessionMapper.selectList(wrapper);
    }

    /**
     * 查询患者的会话历史
     */
    public List<AgentSession> listByCustomer(String customerId, int limit) {
        LambdaQueryWrapper<AgentSession> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentSession::getCustomerId, customerId)
            .orderByDesc(AgentSession::getCreatedAt)
            .last("LIMIT " + limit);

        return sessionMapper.selectList(wrapper);
    }

    private AgentContext retainedContext(AgentContext context) {
        if (contextEvidenceRetentionService == null) {
            return context;
        }
        return contextEvidenceRetentionService.retainForPersistence(context);
    }
}
