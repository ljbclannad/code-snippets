ALTER TABLE b_ai_group_memory_conflict
    ADD COLUMN resolution_reason VARCHAR(255) NULL COMMENT '冲突关闭原因' AFTER resolution_status,
    ADD COLUMN resolution_note VARCHAR(500) NULL COMMENT '冲突处理备注' AFTER resolution_reason;

CREATE TABLE IF NOT EXISTS b_ai_group_memory_review_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    candidate_id BIGINT NOT NULL COMMENT '候选记忆ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    group_id VARCHAR(64) NOT NULL COMMENT '群组ID',
    action_type VARCHAR(32) NOT NULL COMMENT '动作类型: CONFIRM/REJECT/ARBITRATE',
    result_status VARCHAR(32) NOT NULL COMMENT '结果状态',
    reviewer VARCHAR(64) NOT NULL COMMENT '审核人',
    resolution_reason VARCHAR(255) NULL COMMENT '关闭原因',
    remarks VARCHAR(500) NULL COMMENT '审核备注',
    related_formal_id BIGINT NULL COMMENT '关联正式记忆ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_memory_review_candidate (candidate_id),
    INDEX idx_memory_review_user_group (customer_id, group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长期记忆审核审计表';
