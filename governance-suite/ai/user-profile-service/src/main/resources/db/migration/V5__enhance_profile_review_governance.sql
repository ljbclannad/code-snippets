ALTER TABLE b_user_profile_field_conflict
    ADD COLUMN resolution_reason VARCHAR(255) NULL COMMENT '冲突关闭原因' AFTER resolution_status,
    ADD COLUMN resolution_note VARCHAR(500) NULL COMMENT '冲突处理备注' AFTER resolution_reason;

CREATE TABLE IF NOT EXISTS b_user_profile_field_review_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    candidate_field_id BIGINT NOT NULL COMMENT '候选字段ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    field_path VARCHAR(128) NOT NULL COMMENT '字段路径',
    action_type VARCHAR(32) NOT NULL COMMENT '动作类型: CONFIRM/REJECT/ARBITRATE',
    result_status VARCHAR(32) NOT NULL COMMENT '结果状态',
    reviewer VARCHAR(64) NOT NULL COMMENT '审核人',
    resolution_reason VARCHAR(255) NULL COMMENT '关闭原因',
    remarks VARCHAR(500) NULL COMMENT '审核备注',
    related_formal_value VARCHAR(255) NULL COMMENT '关联正式值',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_profile_review_candidate (candidate_field_id),
    INDEX idx_profile_review_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='画像字段审核审计表';
