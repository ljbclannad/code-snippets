package com.health.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 随访任务实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("follow_up_task")
public class FollowUpTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskId;

    private String customerId;

    private String taskDescription;

    private LocalDate dueDate;

    private TaskStatusEnum status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private String notes;

    public enum TaskStatusEnum {
        PENDING,
        COMPLETED,
        CANCELLED
    }
}
