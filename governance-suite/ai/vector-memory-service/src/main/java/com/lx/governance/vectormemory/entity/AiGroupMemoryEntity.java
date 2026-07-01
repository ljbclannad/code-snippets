package com.lx.governance.vectormemory.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI群组记忆实体类
 * 映射表: b_ai_group_memory
 */
@Data
public class AiGroupMemoryEntity {

    /**
     * 主键
     */
    private Long id;

    /**
     * 用户ID
     */
    private String customerId;

    /**
     * 群组ID
     */
    private String groupId;

    /**
     * 记忆类型: SYMPTOM/ALLERGY/MEDICATION/DIET/PREFERENCE/OTHER
     */
    private String memoryType;

    /**
     * 记忆关键词
     */
    private String memoryKey;

    /**
     * 记忆内容
     */
    private String memoryContent;

    /**
     * 记忆表现形态: EVIDENCE/SUMMARY
     */
    private String memoryRepresentation;

    /**
     * 摘要分组键
     */
    private String summaryGroupKey;

    /**
     * 摘要关联的主证据记忆ID
     */
    private Long summaryOfMemoryId;

    /**
     * 生命周期状态
     */
    private String lifecycleStatus;

    /**
     * 保留策略
     */
    private String retentionPolicy;

    /**
     * 过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 衰减分
     */
    private BigDecimal decayScore;

    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 最后强化时间
     */
    private LocalDateTime lastReinforcedAt;

    /**
     * 来源累计次数
     */
    private Integer sourceCount;

    /**
     * 证据累计次数
     */
    private Integer evidenceCount;

    /**
     * 冲突治理状态
     */
    private String conflictStatus;

    /**
     * 被哪条更新记忆覆盖
     */
    private Long supersededBy;

    /**
     * 归档时间
     */
    private LocalDateTime archivedAt;

    /**
     * 归档原因
     */
    private String archiveReason;

    /**
     * 决策原因码
     */
    private String decisionReasonCode;

    /**
     * 向量数据库中的ID
     */
    private String embeddingId;

    /**
     * 置信度分数 0.000-1.000
     */
    private BigDecimal confidenceScore;

    /**
     * 来源消息
     */
    private String sourceMessage;

    /**
     * 提取时间
     */
    private LocalDateTime extractedAt;

    /**
     * 逻辑删除: 0=有效, 1=已删除
     */
    private Integer isDeleted;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
