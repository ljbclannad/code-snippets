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
 * 待确认任务实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_pending_task")
public class PendingTask {

    /**
     * 任务ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Agent会话ID
     */
    private String agentSessionId;

    /**
     * 患者ID
     */
    private String customerId;

    /**
     * 医生ID
     */
    private String doctorId;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具描述
     */
    private String toolDescription;

    /**
     * 输入参数（JSON）
     */
    private String inputJson;

    /**
     * 执行原因
     */
    private String reason;

    /**
     * 状态
     */
    private String status;

    /**
     * 审核意见
     */
    private String reviewComment;

    /**
     * 审核时间
     */
    private LocalDateTime reviewedAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 过期时间
     */
    private LocalDateTime expireAt;
}
