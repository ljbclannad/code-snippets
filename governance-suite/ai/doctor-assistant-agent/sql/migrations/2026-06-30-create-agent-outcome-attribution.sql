USE doctor_assistant;

CREATE TABLE IF NOT EXISTS agent_outcome_attribution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    run_id VARCHAR(64) NOT NULL COMMENT '平台runId/会话ID',
    session_id VARCHAR(64) COMMENT 'Agent会话ID',
    scene VARCHAR(64) NOT NULL COMMENT '场景标识',
    task_type VARCHAR(64) COMMENT '任务类型',
    primary_cause VARCHAR(64) NOT NULL COMMENT '主归因原因',
    secondary_causes_json JSON COMMENT '次级原因列表',
    confidence DECIMAL(5,4) COMMENT '归因置信度',
    evidence_summary_json JSON COMMENT '证据摘要',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_agent_outcome_attribution_run_id (run_id),
    INDEX idx_agent_outcome_attribution_scene_task_created (scene, task_type, created_at),
    INDEX idx_agent_outcome_attribution_primary_cause_created (primary_cause, created_at)
) COMMENT='Agent结果归因表';
