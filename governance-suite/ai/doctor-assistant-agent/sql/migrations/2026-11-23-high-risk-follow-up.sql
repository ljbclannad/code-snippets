-- 在 doctor_assistant 数据库执行。审批记录和站内投递共用本地事务。
CREATE TABLE IF NOT EXISTS agent_follow_up_reminder (
    customer_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    pending_task_id BIGINT UNIQUE,
    body VARCHAR(255) NOT NULL,
    delivered_at DATETIME(6),
    PRIMARY KEY (customer_id, request_id),
    INDEX idx_follow_up_inbox (customer_id, delivered_at, pending_task_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
