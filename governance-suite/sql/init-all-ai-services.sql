-- =============================================
-- AI 服务完整初始化脚本
-- 包含所有 AI 相关服务所需的数据库和表
-- =============================================

-- 创建 governance 数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS governance DEFAULT CHARACTER SET utf8mb4;

USE governance;

-- =============================================
-- Part 1: AI 记忆功能表（vector-memory-service）
-- =============================================

-- 1. 群组记忆表
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

-- 2. 用户全局记忆表
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
  UNIQUE KEY `uk_customer_type_key` (`customer_id`, `memory_type`, `memory_key`, `is_deleted`),
  KEY `idx_embedding_id` (`embedding_id`),
  KEY `idx_verified` (`verified`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI用户全局记忆表';

-- 3. 聊天历史表（对齐 ai-chat-service 当前实体 t_ai_chat_history）
DROP TABLE IF EXISTS `t_ai_chat_history`;
CREATE TABLE `t_ai_chat_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
  `conversation_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
  `user_message` TEXT NOT NULL COMMENT '用户消息',
  `ai_response` TEXT NOT NULL COMMENT 'AI回复',
  `prompt_tokens` INT DEFAULT NULL COMMENT 'Prompt tokens',
  `completion_tokens` INT DEFAULT NULL COMMENT 'Completion tokens',
  `total_tokens` INT DEFAULT NULL COMMENT 'Total tokens',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_conversation` (`customer_id`, `conversation_id`),
  KEY `idx_customer_deleted_time` (`customer_id`, `is_deleted`, `create_time`),
  KEY `idx_conversation_time` (`conversation_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI聊天历史表';

-- =============================================
-- Part 2: 用户画像服务表（user-profile-service）
-- =============================================

-- 4. 客户基本信息表
DROP TABLE IF EXISTS `t_customer`;
CREATE TABLE `t_customer` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '客户ID',
  `name` VARCHAR(128) NOT NULL COMMENT '姓名',
  `age` INT COMMENT '年龄',
  `gender` VARCHAR(16) COMMENT '性别: MALE/FEMALE/OTHER',
  `phone` VARCHAR(32) COMMENT '手机号',
  `email` VARCHAR(128) COMMENT '邮箱',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_id` (`customer_id`, `is_deleted`),
  KEY `idx_phone` (`phone`),
  KEY `idx_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户基本信息表';

-- 5. 健康评估表
DROP TABLE IF EXISTS `t_health_assessment`;
CREATE TABLE `t_health_assessment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` VARCHAR(64) NOT NULL COMMENT '客户ID',
  `bmi` DECIMAL(5,2) COMMENT 'BMI指数',
  `blood_pressure` VARCHAR(32) COMMENT '血压 (格式: 120/80)',
  `blood_sugar` DECIMAL(5,2) COMMENT '血糖 (mmol/L)',
  `assessment_date` DATE NOT NULL COMMENT '评估日期',
  `health_goals` TEXT COMMENT '健康目标',
  `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_date` (`customer_id`, `assessment_date`, `is_deleted`),
  KEY `idx_assessment_date` (`assessment_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='健康评估表';

-- =============================================
-- Part 3: 测试数据
-- =============================================

-- 插入测试客户
INSERT INTO `t_customer`
  (`customer_id`, `name`, `age`, `gender`, `phone`, `email`)
VALUES
  ('test-customer-001', '张三', 35, 'MALE', '13800138000', 'zhangsan@example.com'),
  ('test-customer-002', '李四', 28, 'FEMALE', '13900139000', 'lisi@example.com'),
  ('user123', '王五', 42, 'MALE', '13700137000', 'wangwu@example.com');

-- 插入健康评估数据
INSERT INTO `t_health_assessment`
  (`customer_id`, `bmi`, `blood_pressure`, `blood_sugar`, `assessment_date`, `health_goals`)
VALUES
  ('test-customer-001', 24.5, '140/90', 5.8, CURDATE(), '控制血压，减少盐分摄入'),
  ('test-customer-002', 22.3, '115/75', 5.1, CURDATE(), '保持健康体重，增加运动'),
  ('user123', 26.8, '135/85', 6.2, CURDATE(), '降低血糖，控制饮食');

-- 插入用户全局记忆
INSERT INTO `b_ai_user_memory`
  (`customer_id`, `memory_type`, `memory_key`, `memory_content`, `confidence_score`, `source`, `verified`)
VALUES
  ('test-customer-001', 'preference', 'diet', '喜欢清淡饮食，不吃辣', 0.95, 'chat', 1),
  ('test-customer-001', 'health_condition', 'allergy', '对海鲜过敏', 0.98, 'chat', 1),
  ('user123', 'ALLERGY', '青霉素', '对青霉素过敏', 1.0, 'MANUAL', 1),
  ('user123', 'CHRONIC_DISEASE', '高血压', '高血压患者，正在服用降压药', 1.0, 'MANUAL', 1);

COMMIT;

-- =============================================
-- 验证数据
-- =============================================

SELECT '========================================' AS '';
SELECT 'AI 服务数据库初始化完成' AS '';
SELECT '========================================' AS '';

SELECT '' AS '';
SELECT '=== 客户信息 ===' AS '';
SELECT customer_id, name, age, gender, phone FROM t_customer WHERE is_deleted = 0;

SELECT '' AS '';
SELECT '=== 健康评估 ===' AS '';
SELECT customer_id, bmi, blood_pressure, blood_sugar, assessment_date FROM t_health_assessment WHERE is_deleted = 0;

SELECT '' AS '';
SELECT '=== 用户全局记忆 ===' AS '';
SELECT customer_id, memory_type, memory_key, memory_content, verified FROM b_ai_user_memory WHERE is_deleted = 0;

SELECT '' AS '';
SELECT '=== 表统计 ===' AS '';
SELECT
  'b_ai_group_memory' AS table_name, COUNT(*) AS row_count FROM b_ai_group_memory WHERE is_deleted = 0
UNION ALL
SELECT 'b_ai_user_memory', COUNT(*) FROM b_ai_user_memory WHERE is_deleted = 0
UNION ALL
SELECT 't_ai_chat_history', COUNT(*) FROM t_ai_chat_history WHERE is_deleted = 0
UNION ALL
SELECT 't_customer', COUNT(*) FROM t_customer WHERE is_deleted = 0
UNION ALL
SELECT 't_health_assessment', COUNT(*) FROM t_health_assessment WHERE is_deleted = 0;
