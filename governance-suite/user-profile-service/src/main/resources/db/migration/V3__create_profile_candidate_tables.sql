CREATE TABLE IF NOT EXISTS b_user_profile_candidate_field (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    field_path VARCHAR(128) NOT NULL COMMENT '字段路径',
    normalized_value VARCHAR(255) NOT NULL COMMENT '归一化值',
    raw_value VARCHAR(255) NOT NULL COMMENT '原始值',
    confidence_score DECIMAL(5,3) NOT NULL COMMENT '置信度',
    status VARCHAR(16) NOT NULL COMMENT '状态: CANDIDATE/CONFIRMED/REJECTED/CONFLICT',
    hit_count INT NOT NULL DEFAULT 1 COMMENT '命中次数',
    source VARCHAR(64) COMMENT '来源',
    reviewed_by VARCHAR(64) COMMENT '审核人',
    reviewed_at DATETIME COMMENT '审核时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_profile_candidate_customer (customer_id),
    INDEX idx_profile_candidate_field (customer_id, field_path),
    INDEX idx_profile_candidate_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像候选字段表';

CREATE TABLE IF NOT EXISTS b_user_profile_field_conflict (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    candidate_field_id BIGINT NOT NULL COMMENT '候选字段ID',
    conflict_target_type VARCHAR(16) NOT NULL COMMENT '冲突对象类型: CANDIDATE/PROFILE',
    conflict_target_value VARCHAR(255) NOT NULL COMMENT '冲突对象值',
    conflict_reason VARCHAR(255) NOT NULL COMMENT '冲突原因',
    resolution_status VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN/RESOLVED/REJECTED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_profile_conflict_candidate (candidate_field_id),
    INDEX idx_profile_conflict_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像字段冲突记录表';
