-- 死信消息表
CREATE TABLE IF NOT EXISTS t_dead_letter_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    exchange VARCHAR(255) NOT NULL COMMENT '交换机名称',
    routing_key VARCHAR(255) NOT NULL COMMENT '路由键',
    message_body TEXT NOT NULL COMMENT '消息体',
    failure_reason TEXT COMMENT '失败原因',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    status VARCHAR(20) NOT NULL COMMENT '状态: PENDING, RETRYING, RESOLVED, FAILED',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    last_retry_at DATETIME COMMENT '最后重试时间',
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='死信消息表';
