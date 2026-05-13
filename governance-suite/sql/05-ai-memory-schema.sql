-- =============================================
-- AI 记忆功能数据库初始化脚本
-- =============================================

USE governance;

-- 1. 创建群组记忆表
DROP TABLE IF EXISTS `b_ai_group_memory`;
CREATE TABLE `b_ai_group_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `group_id` VARCHAR(64) NOT NULL COMMENT '群组ID',
  `memory_type` VARCHAR(32) NOT NULL COMMENT '记忆类型: SYMPTOM/ALLERGY/MEDICATION/DIET/PREFERENCE/OTHER',
  `memory_key` VARCHAR(128) NOT NULL COMMENT '记忆关键词',
  `memory_content` TEXT NOT NULL COMMENT '记忆内容',
  `embedding_id` VARCHAR(255) COMMENT '向量数据库中的ID',
  `confidence_score` DECIMAL(4,3) COMMENT '置信度分数 0.000-1.000',
  `source_message` TEXT COMMENT '来源消息',
  `extracted_at` DATETIME COMMENT '提取时间',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_group` (`customer_id`, `group_id`, `is_deleted`),
  KEY `idx_memory_type` (`memory_type`),
  KEY `idx_embedding_id` (`embedding_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI群组记忆表';

-- 2. 创建用户全局记忆表
DROP TABLE IF EXISTS `b_ai_user_memory`;
CREATE TABLE `b_ai_user_memory` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `memory_type` VARCHAR(32) NOT NULL COMMENT '记忆类型: ALLERGY/CHRONIC_DISEASE/MEDICATION/DIET_RESTRICTION/OTHER',
  `memory_key` VARCHAR(128) NOT NULL COMMENT '记忆关键词',
  `memory_content` TEXT NOT NULL COMMENT '记忆内容',
  `embedding_id` VARCHAR(255) COMMENT '向量数据库中的ID',
  `confidence_score` DECIMAL(4,3) COMMENT '置信度分数 0.000-1.000',
  `source` VARCHAR(64) COMMENT '来源: CHAT/MANUAL/IMPORT',
  `verified` TINYINT DEFAULT 0 COMMENT '是否已验证: 0=未验证, 1=已验证',
  `verified_by` VARCHAR(64) COMMENT '验证人ID',
  `verified_at` DATETIME COMMENT '验证时间',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_type_key` (`customer_id`, `memory_type`, `memory_key`),
  KEY `idx_embedding_id` (`embedding_id`),
  KEY `idx_verified` (`verified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI用户全局记忆表';

-- 3. 创建聊天历史表
DROP TABLE IF EXISTS `b_ai_chat_history`;
CREATE TABLE `b_ai_chat_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `group_id` VARCHAR(64) NOT NULL COMMENT '群组ID',
  `conversation_id` VARCHAR(128) NOT NULL COMMENT 'Dify会话ID',
  `user_message` TEXT NOT NULL COMMENT '用户消息',
  `ai_response` TEXT COMMENT 'AI回复',
  `role` VARCHAR(32) COMMENT '角色类型: doctor/nutritionist/customer',
  `trace_id` VARCHAR(64) COMMENT '追踪ID',
  `response_time` INT COMMENT '响应时间(毫秒)',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_group` (`customer_id`, `group_id`, `is_deleted`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI聊天历史表';

-- 4. 插入测试数据（可选）
INSERT INTO `b_ai_user_memory`
  (`customer_id`, `memory_type`, `memory_key`, `memory_content`, `confidence_score`, `source`, `verified`)
VALUES
  ('user123', 'ALLERGY', '青霉素', '对青霉素过敏', 1.0, 'MANUAL', 1),
  ('user123', 'CHRONIC_DISEASE', '高血压', '高血压患者，正在服用降压药', 1.0, 'MANUAL', 1);

COMMIT;
