USE order_db;

CREATE TABLE t_order_admin_activity_audit (
    audit_id BIGINT NOT NULL AUTO_INCREMENT,
    action VARCHAR(40) NOT NULL,
    actor_user_id BIGINT NULL COMMENT '后台自动操作为空',
    target_id VARCHAR(128) NULL COMMENT '任务 ID、事件 ID 或检索条件指纹',
    outcome VARCHAR(16) NOT NULL,
    detail VARCHAR(500) NULL COMMENT '不包含客户身份及原始筛选值',
    trace_id VARCHAR(64) NULL,
    occurred_at TIMESTAMP NOT NULL,
    PRIMARY KEY (audit_id),
    KEY idx_admin_activity_target (target_id, occurred_at),
    KEY idx_admin_activity_actor (actor_user_id, occurred_at),
    KEY idx_admin_activity_action (action, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='后台订单操作审计';
