-- =============================================
-- 用户画像服务数据库初始化脚本
-- Service: user-profile-service
-- =============================================

USE governance;

-- 1. 创建客户基本信息表
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

-- 2. 创建健康评估表
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

-- 3. 插入测试数据
INSERT INTO `t_customer`
  (`customer_id`, `name`, `age`, `gender`, `phone`, `email`)
VALUES
  ('test-customer-001', '张三', 35, 'MALE', '13800138000', 'zhangsan@example.com'),
  ('test-customer-002', '李四', 28, 'FEMALE', '13900139000', 'lisi@example.com'),
  ('user123', '王五', 42, 'MALE', '13700137000', 'wangwu@example.com');

INSERT INTO `t_health_assessment`
  (`customer_id`, `bmi`, `blood_pressure`, `blood_sugar`, `assessment_date`, `health_goals`)
VALUES
  ('test-customer-001', 24.5, '140/90', 5.8, CURDATE(), '控制血压，减少盐分摄入'),
  ('test-customer-002', 22.3, '115/75', 5.1, CURDATE(), '保持健康体重，增加运动'),
  ('user123', 26.8, '135/85', 6.2, CURDATE(), '降低血糖，控制饮食');

COMMIT;

-- 验证数据插入
SELECT '=== 客户信息 ===' AS '';
SELECT customer_id, name, age, gender, phone FROM t_customer WHERE is_deleted = 0;

SELECT '=== 健康评估 ===' AS '';
SELECT customer_id, bmi, blood_pressure, blood_sugar, assessment_date FROM t_health_assessment WHERE is_deleted = 0;
