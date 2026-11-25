package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.lx.governance.aichat.dto.TaskTriggerReason;
import com.lx.governance.aichat.dto.TaskTriggerRoute;
import com.lx.governance.aichat.dto.TaskType;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionContext {

    /** 意图分析未完成，不能据此推断患者需要补全画像。 */
    public static final String UNKNOWN_INTENT = "unknown";

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 用户ID
     */
    private String customerId;

    /**
     * 上游显式用户ID，缺省可与 customerId 相同。
     */
    private String userId;

    /**
     * 群组ID
     */
    private String groupId;

    /**
     * 租户ID
     */
    private String tenantId;

    /**
     * 当前话题
     */
    private String currentTopic;

    /**
     * 对话阶段：consulting（咨询中）、idle（闲聊）
     */
    private String conversationPhase;

    /**
     * 提及的实体（人名、地名、疾病名等）
     */
    @Builder.Default
    private List<String> mentionedEntities = new ArrayList<>();

    /**
     * 提取的结构化数据（如：血压值、体重等）
     */
    @Builder.Default
    private Map<String, Object> extractedData = new HashMap<>();

    /**
     * 最近消息摘要（滑动窗口）
     */
    @Builder.Default
    private List<MessageSummary> recentSummaries = new ArrayList<>();

    /**
     * 用户意图
     */
    private String userIntent;

    /**
     * 待确认问题
     */
    @Builder.Default
    private List<String> pendingQuestions = new ArrayList<>();

    /**
     * 复杂度：simple（简单）、complex（复杂）
     */
    private String complexity;

    /**
     * 会话开始时间
     */
    private Long sessionStartTime;

    /**
     * 最后更新时间
     */
    private Long lastUpdateTime;

    /**
     * 消息计数
     */
    private Integer messageCount;

    /**
     * 上下文版本号。
     * 每次成功写入当前会话上下文时递增，用于排查乱序覆盖问题。
     */
    private Long contextVersion;

    /**
     * 最近一次成功写入上下文的消息时间戳。
     */
    private Long lastMessageAt;

    /**
     * 最近一次成功写入上下文的请求 ID。
     */
    private String lastProcessedRequestId;

    /**
     * 最近一次任务路由去向。
     */
    private TaskTriggerRoute lastTaskTriggerRoute;

    /**
     * 最近一次任务类型。
     */
    private TaskType lastTaskType;

    /**
     * 最近一次命中的触发原因。
     */
    @Builder.Default
    private List<TaskTriggerReason> lastTaskTriggerReasons = new ArrayList<>();

    /**
     * 最近一次触发原因摘要。
     */
    private String lastTaskTriggerSummary;

    /**
     * 最近一次任务路由判定时间。
     */
    private Long lastTaskTriggerAt;

    /**
     * 最近一次 agent 返回的人工复核状态。
     */
    private String lastAgentReviewStatus;

    /**
     * 最近一次 agent 待审批任务 ID。
     */
    private Long lastAgentPendingTaskId;

    /**
     * 最近一次 agent 执行路径。
     */
    private String lastAgentExecutionPath;

    /**
     * 最近一次 agent 结果记录时间。
     */
    private Long lastAgentOutcomeAt;
}
