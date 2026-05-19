package com.health.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.health.agent.mapper.PendingTaskMapper;
import com.health.agent.model.PendingTask;
import com.health.agent.model.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认任务服务
 */
@Slf4j
@Service
public class PendingTaskService {

    @Autowired
    private PendingTaskMapper taskMapper;

    /**
     * 保存待确认任务
     */
    public void save(PendingTask task) {
        taskMapper.insert(task);
        log.info("创建待确认任务: taskId={}, tool={}, customerId={}",
            task.getId(), task.getToolName(), task.getCustomerId());
    }

    /**
     * 根据ID查询任务
     */
    public PendingTask getById(Long taskId) {
        return taskMapper.selectById(taskId);
    }

    /**
     * 查询医生的待确认任务列表
     */
    public List<PendingTask> listPendingByDoctor(String doctorId) {
        LambdaQueryWrapper<PendingTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PendingTask::getDoctorId, doctorId)
            .eq(PendingTask::getStatus, TaskStatus.PENDING.name())
            .orderByDesc(PendingTask::getCreatedAt);

        return taskMapper.selectList(wrapper);
    }

    /**
     * 查询会话的待确认任务
     */
    public List<PendingTask> listBySession(String sessionId) {
        LambdaQueryWrapper<PendingTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PendingTask::getAgentSessionId, sessionId)
            .orderByDesc(PendingTask::getCreatedAt);

        return taskMapper.selectList(wrapper);
    }

    /**
     * 批准任务
     */
    public void approve(Long taskId, String reviewComment) {
        PendingTask task = getById(taskId);
        if (task == null) {
            log.warn("任务不存在: {}", taskId);
            return;
        }

        task.setStatus(TaskStatus.APPROVED.name());
        task.setReviewComment(reviewComment);
        task.setReviewedAt(LocalDateTime.now());

        taskMapper.updateById(task);
        log.info("批准任务: taskId={}, tool={}", taskId, task.getToolName());
    }

    /**
     * 拒绝任务
     */
    public void reject(Long taskId, String reviewComment) {
        PendingTask task = getById(taskId);
        if (task == null) {
            log.warn("任务不存在: {}", taskId);
            return;
        }

        task.setStatus(TaskStatus.REJECTED.name());
        task.setReviewComment(reviewComment);
        task.setReviewedAt(LocalDateTime.now());

        taskMapper.updateById(task);
        log.info("拒绝任务: taskId={}, tool={}", taskId, task.getToolName());
    }

    /**
     * 清理过期任务
     */
    public int cleanExpiredTasks() {
        LambdaQueryWrapper<PendingTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PendingTask::getStatus, TaskStatus.PENDING.name())
            .lt(PendingTask::getExpireAt, LocalDateTime.now());

        List<PendingTask> expiredTasks = taskMapper.selectList(wrapper);

        for (PendingTask task : expiredTasks) {
            task.setStatus(TaskStatus.EXPIRED.name());
            taskMapper.updateById(task);
        }

        log.info("清理过期任务: count={}", expiredTasks.size());
        return expiredTasks.size();
    }
}
