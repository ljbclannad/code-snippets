package com.health.agent.dto.agent;

import java.util.Map;

import com.health.agent.dto.patient.AgentTaskConstraints;
import com.health.agent.dto.patient.AgentTaskSource;
import com.health.agent.dto.patient.MemorySnapshot;
import com.health.agent.dto.patient.TaskType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一的 Agent 内部执行命令。
 *
 * <p>医生侧 {@code /api/agent/execute} 与患者升级链路
 * {@code /api/patient-tasks/analyze} 最终都先归一化成这份内部命令，
 * 后续再决定是否进入真实 ReAct Agent 执行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionCommand {
    /**
     * 这次 Agent 执行要完成的自然语言目标。
     */
    private String goal;

    /**
     * 任务对应的患者 ID。
     */
    private String customerId;

    /**
     * 当前责任医生 ID。
     */
    private String doctorId;

    /**
     * 上游主对话会话 ID。
     */
    private String conversationId;

    /**
     * 跨服务链路追踪 ID。
     */
    private String correlationId;

    /**
     * 请求来源。
     */
    private AgentTaskSource source;

    /**
     * 这次执行对应的患者任务类型。
     */
    private TaskType taskType;

    /**
     * 当前轮患者原始消息。
     */
    private String patientMessage;

    /**
     * ai-chat-service 预加载的会话上下文快照。
     */
    private Map<String, Object> sessionContext;

    /**
     * ai-chat-service 预加载的画像快照。
     */
    private Map<String, Object> profileSnapshot;

    /**
     * ai-chat-service 预加载的记忆快照。
     */
    private MemorySnapshot memorySnapshot;

    /**
     * 上游任务触发决策结果。
     */
    private Map<String, Object> triggerDecision;

    /**
     * 上游附加约束，用于限制这次 Agent 执行的边界。
     */
    private AgentTaskConstraints constraints;
}
