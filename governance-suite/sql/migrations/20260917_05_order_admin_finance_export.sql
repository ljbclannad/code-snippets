USE order_db;

CREATE TABLE t_order_admin_export_task (
    task_id VARCHAR(36) NOT NULL COMMENT '服务端生成任务 UUID',
    owner_user_id BIGINT NOT NULL COMMENT '创建者用户 ID',
    criteria_fingerprint VARCHAR(64) NOT NULL COMMENT '规范化筛选条件指纹',
    sort_field VARCHAR(32) NOT NULL COMMENT '冻结排序字段',
    sort_direction VARCHAR(4) NOT NULL COMMENT '冻结排序方向',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/READY/FAILED/EXPIRED',
    row_count INT NOT NULL DEFAULT 0 COMMENT '冻结快照行数',
    file_key VARCHAR(64) NULL COMMENT '受控存储文件键',
    failure_summary VARCHAR(500) NULL COMMENT '失败摘要',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP NULL,
    completed_at TIMESTAMP NULL,
    expires_at TIMESTAMP NULL,
    PRIMARY KEY (task_id),
    KEY idx_export_claim (status, created_at),
    KEY idx_export_expire (status, expires_at),
    KEY idx_export_owner (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='财务订单异步导出任务';

CREATE TABLE t_order_admin_export_row (
    task_id VARCHAR(36) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    customer_id VARCHAR(64) NULL,
    product_id VARCHAR(64) NULL,
    quantity INT NULL,
    amount_cents BIGINT NULL,
    status VARCHAR(16) NULL,
    payment_status VARCHAR(16) NULL,
    pay_no VARCHAR(64) NULL,
    payment_updated_at TIMESTAMP NULL,
    created_at TIMESTAMP NULL,
    PRIMARY KEY (task_id, order_no),
    KEY idx_export_row_created (task_id, created_at, order_no),
    KEY idx_export_row_amount (task_id, amount_cents, order_no),
    KEY idx_export_row_payment (task_id, payment_updated_at, order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='财务订单导出冻结快照行';
