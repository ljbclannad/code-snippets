ALTER TABLE b_ai_group_memory_candidate
    ADD COLUMN lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE' COMMENT '生命周期状态' AFTER status,
    ADD COLUMN worthiness_score DECIMAL(5,3) NULL COMMENT '长期写入价值分' AFTER confidence_score,
    ADD COLUMN decision_reason_code VARCHAR(64) NULL COMMENT '写入治理决策原因码' AFTER lifecycle_status,
    ADD COLUMN last_reinforced_at DATETIME NULL COMMENT '最后一次强化时间' AFTER last_seen_at,
    ADD COLUMN source_count INT NOT NULL DEFAULT 1 COMMENT '来源累计次数' AFTER hit_count,
    ADD COLUMN evidence_count INT NOT NULL DEFAULT 1 COMMENT '证据累计次数' AFTER source_count;

ALTER TABLE b_ai_group_memory
    ADD COLUMN lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED' COMMENT '生命周期状态' AFTER memory_content,
    ADD COLUMN retention_policy VARCHAR(64) NULL COMMENT '保留策略' AFTER lifecycle_status,
    ADD COLUMN expires_at DATETIME NULL COMMENT '过期时间' AFTER retention_policy,
    ADD COLUMN decay_score DECIMAL(5,3) NULL COMMENT '衰减分' AFTER expires_at,
    ADD COLUMN last_accessed_at DATETIME NULL COMMENT '最后访问时间' AFTER decay_score,
    ADD COLUMN last_reinforced_at DATETIME NULL COMMENT '最后强化时间' AFTER last_accessed_at,
    ADD COLUMN source_count INT NOT NULL DEFAULT 1 COMMENT '来源累计次数' AFTER last_reinforced_at,
    ADD COLUMN evidence_count INT NOT NULL DEFAULT 1 COMMENT '证据累计次数' AFTER source_count,
    ADD COLUMN conflict_status VARCHAR(32) NULL COMMENT '冲突治理状态' AFTER evidence_count,
    ADD COLUMN superseded_by BIGINT NULL COMMENT '被哪条新记忆覆盖' AFTER conflict_status,
    ADD COLUMN archived_at DATETIME NULL COMMENT '归档时间' AFTER superseded_by,
    ADD COLUMN archive_reason VARCHAR(255) NULL COMMENT '归档原因' AFTER archived_at,
    ADD COLUMN decision_reason_code VARCHAR(64) NULL COMMENT '转正或治理决策原因码' AFTER archive_reason;

ALTER TABLE b_ai_group_memory_review_audit
    ADD COLUMN decision_reason_code VARCHAR(64) NULL COMMENT '审计决策原因码' AFTER resolution_reason;
