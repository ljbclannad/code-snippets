CREATE TABLE IF NOT EXISTS b_user_profile_behavior_preference (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '用户ID',
    diet_preference VARCHAR(255) COMMENT '饮食偏好',
    exercise_frequency VARCHAR(255) COMMENT '运动频率',
    check_in_streak INT COMMENT '连续打卡天数',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_behavior_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式行为偏好画像表';
