package com.health.agent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.mapper.AgentExecutionLogMapper;
import com.health.agent.model.AgentExecutionLog;
import com.health.agent.model.AgentResult;
import com.health.agent.model.PendingTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 审计服务
 */
@Service
public class AgentAuditService {

    @Autowired
    private AgentExecutionLogMapper executionLogMapper;

    public void recordPendingTaskCreated(PendingTask task, String goal) {
        AgentExecutionLog log = new AgentExecutionLog();
        log.setSessionId(task.getAgentSessionId());
        log.setPendingTaskId(task.getId());
        log.setCustomerId(task.getCustomerId());
        log.setDoctorId(task.getDoctorId());
        log.setGoal(goal);
        log.setResultStatus("PENDING_APPROVAL");
        log.setToolName(task.getToolName());
        log.setToolInputJson(task.getInputJson());
        log.setApprovalStatus(task.getStatus());
        log.setErrorMessage(task.getReason());
        log.setCreatedAt(LocalDateTime.now());
        executionLogMapper.insert(log);
    }

    public void recordTaskReview(Long taskId, String approvalStatus, String reviewComment) {
        LambdaQueryWrapper<AgentExecutionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentExecutionLog::getPendingTaskId, taskId)
            .orderByDesc(AgentExecutionLog::getCreatedAt)
            .last("LIMIT 1");

        List<AgentExecutionLog> logs = executionLogMapper.selectList(wrapper);
        if (logs.isEmpty()) {
            return;
        }

        AgentExecutionLog log = logs.get(0);
        log.setApprovalStatus(approvalStatus);
        log.setReviewComment(reviewComment);
        log.setReviewedAt(LocalDateTime.now());
        executionLogMapper.updateById(log);
    }

    public void recordToolExecution(
        String sessionId,
        String customerId,
        String doctorId,
        String goal,
        String toolName,
        String toolInputJson,
        String toolOutputJson,
        String approvalStatus,
        String resultStatus
    ) {
        AgentExecutionLog log = new AgentExecutionLog();
        log.setSessionId(sessionId);
        log.setCustomerId(customerId);
        log.setDoctorId(doctorId);
        log.setGoal(goal);
        log.setToolName(toolName);
        log.setToolInputJson(toolInputJson);
        log.setToolOutputJson(toolOutputJson);
        log.setApprovalStatus(approvalStatus);
        log.setResultStatus(resultStatus);
        log.setCreatedAt(LocalDateTime.now());
        executionLogMapper.insert(log);
    }

    public void recordSessionResult(
        String sessionId,
        String customerId,
        String doctorId,
        String goal,
        AgentResult result,
        int totalSteps,
        long latencyMs
    ) {
        AgentExecutionLog log = new AgentExecutionLog();
        log.setSessionId(sessionId);
        log.setCustomerId(customerId);
        log.setDoctorId(doctorId);
        log.setGoal(goal);
        log.setTotalSteps(totalSteps);
        log.setLatencyMs(latencyMs);
        log.setResultStatus(result.getStatus().name());
        log.setFinalAnswer(result.getFinalAnswer());
        log.setErrorMessage(result.getErrorMessage());
        log.setExecutionHistoryJson(JSON.toJSONString(result.getExecutionHistory()));
        log.setCreatedAt(LocalDateTime.now());
        executionLogMapper.insert(log);
    }

    public void recordSafetyBlock(
        String sessionId,
        String customerId,
        String doctorId,
        String goal,
        String category,
        String message,
        String evidenceJson
    ) {
        AgentExecutionLog log = new AgentExecutionLog();
        log.setSessionId(sessionId);
        log.setCustomerId(customerId);
        log.setDoctorId(doctorId);
        log.setGoal(goal);
        log.setResultStatus("SAFETY_BLOCK");
        log.setToolName("medical_safety_rule");
        log.setToolInputJson(evidenceJson);
        log.setApprovalStatus(category);
        log.setErrorMessage(message);
        log.setCreatedAt(LocalDateTime.now());
        executionLogMapper.insert(log);
    }
}
