package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionContext {

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 用户ID
     */
    private String customerId;

    /**
     * 群组ID
     */
    private String groupId;

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
}
