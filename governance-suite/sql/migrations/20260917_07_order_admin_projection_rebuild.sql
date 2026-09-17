USE order_db;

CREATE TABLE t_order_admin_rebuild_task (
    task_id VARCHAR(36) NOT NULL,
    operator_user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/COMPLETED/FAILED',
    phase INT NOT NULL DEFAULT 0 COMMENT '0/1 订单分片，2/3 支付分片，4 完成',
    order_watermark_0 BIGINT NOT NULL,
    order_watermark_1 BIGINT NOT NULL,
    payment_watermark_0 BIGINT NOT NULL,
    payment_watermark_1 BIGINT NOT NULL,
    order_cursor_0 BIGINT NOT NULL DEFAULT 0,
    order_cursor_1 BIGINT NOT NULL DEFAULT 0,
    payment_cursor_0 BIGINT NOT NULL DEFAULT 0,
    payment_cursor_1 BIGINT NOT NULL DEFAULT 0,
    processed_count BIGINT NOT NULL DEFAULT 0,
    success_count BIGINT NOT NULL DEFAULT 0,
    failure_count BIGINT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (task_id),
    KEY idx_rebuild_claim (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单后台读模型回填任务';

CREATE TABLE t_order_admin_rebuild_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    operator_user_id BIGINT NULL COMMENT '创建或重试的操作者；后台事件为空',
    action VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    KEY idx_rebuild_audit_task (task_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='读模型回填操作审计';
