package com.health.agent.service;

import com.health.agent.mapper.FollowUpTaskMapper;
import com.health.agent.model.FollowUpTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 随访任务服务
 */
@Slf4j
@Service
public class FollowUpTaskService {

    @Autowired
    private FollowUpTaskMapper followUpTaskMapper;

    /**
     * 创建随访任务
     */
    public FollowUpTask createTask(String customerId, String taskDescription, LocalDate dueDate, String createdBy) {
        String taskId = "FT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        FollowUpTask task = FollowUpTask.builder()
                .taskId(taskId)
                .customerId(customerId)
                .taskDescription(taskDescription)
                .dueDate(dueDate)
                .status(FollowUpTask.TaskStatusEnum.PENDING)
                .createdBy(createdBy)
                .createdAt(LocalDateTime.now())
                .build();

        followUpTaskMapper.insert(task);
        log.info("创建随访任务成功: taskId={}, customerId={}", taskId, customerId);

        return task;
    }

    /**
     * 根据任务ID查询
     */
    public FollowUpTask getByTaskId(String taskId) {
        return followUpTaskMapper.findByTaskId(taskId);
    }

    /**
     * 查询患者的随访任务
     */
    public List<FollowUpTask> getTasksByCustomerId(String customerId, int limit) {
        return followUpTaskMapper.findByCustomerId(customerId, limit);
    }

    /**
     * 查询患者的待处理任务
     */
    public List<FollowUpTask> getPendingTasks(String customerId) {
        return followUpTaskMapper.findByCustomerIdAndStatus(customerId, "PENDING");
    }

    /**
     * 完成任务
     */
    public void completeTask(String taskId, String notes) {
        FollowUpTask task = followUpTaskMapper.findByTaskId(taskId);
        if (task != null) {
            task.setStatus(FollowUpTask.TaskStatusEnum.COMPLETED);
            task.setCompletedAt(LocalDateTime.now());
            if (notes != null) {
                task.setNotes(notes);
            }
            followUpTaskMapper.updateById(task);
            log.info("完成随访任务: taskId={}", taskId);
        }
    }

    /**
     * 取消任务
     */
    public void cancelTask(String taskId, String notes) {
        FollowUpTask task = followUpTaskMapper.findByTaskId(taskId);
        if (task != null) {
            task.setStatus(FollowUpTask.TaskStatusEnum.CANCELLED);
            if (notes != null) {
                task.setNotes(notes);
            }
            followUpTaskMapper.updateById(task);
            log.info("取消随访任务: taskId={}", taskId);
        }
    }
}
