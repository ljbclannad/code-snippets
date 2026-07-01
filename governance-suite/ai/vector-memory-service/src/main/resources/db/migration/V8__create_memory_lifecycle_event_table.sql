CREATE TABLE IF NOT EXISTS b_ai_group_memory_lifecycle_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    entity_type VARCHAR(32) NOT NULL COMMENT '实体类型: CANDIDATE/CONFIRMED/JOB/COMPACTION',
    entity_id BIGINT NULL COMMENT '实体ID',
    customer_id VARCHAR(64) NULL COMMENT '用户ID',
    group_id VARCHAR(64) NULL COMMENT '群组ID',
    memory_type VARCHAR(32) NULL COMMENT '记忆类型',
    event_type VARCHAR(64) NOT NULL COMMENT '事件类型',
    lifecycle_status VARCHAR(32) NULL COMMENT '生命周期状态',
    reason_code VARCHAR(64) NULL COMMENT '原因码',
    event_payload VARCHAR(1000) NULL COMMENT '事件补充信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_memory_lifecycle_event_entity (entity_type, entity_id),
    INDEX idx_memory_lifecycle_event_user_group (customer_id, group_id),
    INDEX idx_memory_lifecycle_event_type_time (event_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长期记忆生命周期审计事件表';
