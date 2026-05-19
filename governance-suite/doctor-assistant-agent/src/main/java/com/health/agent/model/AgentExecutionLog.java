package com.health.agent.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent执行日志实体
 */
@Data
@TableName("agent_execution_log")
public class AgentExecutionLog {

    /**
     * 日志ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    private String sessionId;

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
     * 总步数
     */
    private Integer totalSteps;

    /**
     * 消耗Token数
     */
    private Integer tokensUsed;

    /**
     * 总耗时(ms)
     */
    private Long latencyMs;

    /**
     * 结果状态
     */
    private String resultStatus;

    /**
     * 最终答案
     */
    private String finalAnswer;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 执行历史（JSON）
     */
    private String executionHistoryJson;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
}
