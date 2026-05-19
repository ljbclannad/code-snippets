package com.health.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Agent会话实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_session")
public class AgentSession {

    /**
     * 会话ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 患者ID
     */
    private String customerId;

    /**
     * 医生ID
     */
    private String doctorId;

    /**
     * 任务目标
     */
    private String goal;

    /**
     * 状态
     */
    private String status;

    /**
     * 执行历史（JSON）
     */
    private String historyJson;

    /**
     * 对话消息（JSON）
     */
    private String messagesJson;

    /**
     * 当前步数
     */
    private Integer currentStep;

    /**
     * 最终答案
     */
    private String finalAnswer;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
}
