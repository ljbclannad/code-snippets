-- =====================================================
-- 分布式系统治理 - 数据库初始化脚本
-- =====================================================
--
-- 包含以下数据库和表：
-- - order_db: 订单服务、Outbox、预约服务
-- - payment_db: 支付服务
-- - inventory_db: 库存服务
-- - user_db: 用户服务
-- - seata: Seata Server (分布式事务协调)
--
-- 执行方式：
--   mysql -h127.0.0.1 -P3306 -uroot -proot < distributed/init-databases.sql
-- =====================================================

-- =====================================================
-- 1. order_db - 订单服务和预约服务共享
-- =====================================================
CREATE DATABASE IF NOT EXISTS `order_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `order_db`;

-- 订单表 (分片表 t_order_0, t_order_1)
CREATE TABLE IF NOT EXISTS `t_order_0` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `customer_id` BIGINT NOT NULL COMMENT '用户ID',
  `product_id` VARCHAR(64) NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '数量',
  `amount_cents` BIGINT NOT NULL COMMENT '金额(分)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-待支付 PAID-已支付 CANCELLED-已取消',
  `version` BIGINT NOT NULL DEFAULT 1 COMMENT '订单事实版本',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单表 - 分片0';

CREATE TABLE IF NOT EXISTS `t_order_1` LIKE `t_order_0`;
ALTER TABLE `t_order_1` COMMENT='订单表 - 分片1';

-- 订单后台读模型（独立非分片查询表）
CREATE TABLE IF NOT EXISTS `t_order_admin_read_model` (
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `customer_id` VARCHAR(64) NULL COMMENT '客户标识',
  `product_id` VARCHAR(64) NULL COMMENT '商品ID',
  `quantity` INT NULL COMMENT '商品数量',
  `amount_cents` BIGINT NULL COMMENT '金额（分）',
  `status` VARCHAR(16) NULL COMMENT '订单状态',
  `order_version` BIGINT NOT NULL DEFAULT 0 COMMENT '订单事件版本，0表示未知',
  `order_event_id` VARCHAR(64) NULL COMMENT '最后应用的订单事件ID',
  `occurred_at` TIMESTAMP NULL COMMENT '订单事件发生时间',
  `payment_status` VARCHAR(16) NULL COMMENT '支付状态，NULL表示未知',
  `pay_no` VARCHAR(64) NULL COMMENT '支付单号',
  `payment_updated_at` TIMESTAMP NULL COMMENT '支付更新时间',
  `payment_version` BIGINT NOT NULL DEFAULT 0 COMMENT '支付事件版本，0表示未知',
  `payment_event_id` VARCHAR(64) NULL COMMENT '最后应用的支付事件ID',
  `payment_occurred_at` TIMESTAMP NULL COMMENT '支付事件发生时间',
  `projected_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '投影写入时间',
  PRIMARY KEY (`order_no`),
  KEY `idx_status_created_at` (`status`, `occurred_at`),
  KEY `idx_payment_status_updated_at` (`payment_status`, `payment_updated_at`),
  KEY `idx_pay_no` (`pay_no`),
  KEY `idx_projected_at` (`projected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单后台读模型';

-- Outbox 消息表
CREATE TABLE IF NOT EXISTS `t_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `event_id` VARCHAR(64) NOT NULL COMMENT '事件ID',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合ID',
  `event_type` VARCHAR(100) NOT NULL COMMENT '事件类型',
  `payload` TEXT NOT NULL COMMENT '消息体(JSON)',
  `status` VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT '状态: NEW-待发送 SENT-已发送 FAILED-失败 DEAD-死信',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  `next_retry_at` TIMESTAMP NULL DEFAULT NULL COMMENT '下次重试时间',
  `error_reason` VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `sent_at` TIMESTAMP NULL DEFAULT NULL COMMENT '发送时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Outbox 消息表';

CREATE TABLE IF NOT EXISTS `t_order_tcc_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `xid` VARCHAR(128) NOT NULL COMMENT 'Seata 全局事务ID',
  `state` VARCHAR(16) NOT NULL COMMENT 'TRIED/COMMITTED/ROLLBACKED',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='订单TCC事务日志';

-- 支付事件消费记录表
CREATE TABLE IF NOT EXISTS `t_payment_event_consume` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `event_id` VARCHAR(64) NOT NULL COMMENT '事件ID',
  `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `consumed_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付事件消费记录';

-- 秒杀订单防重表
CREATE TABLE IF NOT EXISTS `t_seckill_order_guard` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `product_id` VARCHAR(64) NOT NULL COMMENT '商品ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_product` (`user_id`, `product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='秒杀订单防重表';

-- 预约服务相关表
CREATE TABLE IF NOT EXISTS `t_appointment_order` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `schedule_id` BIGINT NOT NULL COMMENT '排班ID',
  `dept_id` BIGINT NOT NULL COMMENT '科室ID',
  `doctor_id` BIGINT NOT NULL COMMENT '医生ID',
  `appointment_date` DATE NOT NULL COMMENT '就诊日期',
  `time_slot` VARCHAR(16) NOT NULL COMMENT '就诊时段',
  `fee_cents` INT NOT NULL COMMENT '挂号费(分)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'RESERVED' COMMENT '状态: RESERVED-已预约 PAID-已支付 CANCELLED-已取消 EXPIRED-已过期',
  `reserved_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预约时间',
  `paid_at` TIMESTAMP NULL DEFAULT NULL COMMENT '支付时间',
  `cancelled_at` TIMESTAMP NULL DEFAULT NULL COMMENT '取消时间',
  `cancel_reason` VARCHAR(255) DEFAULT NULL COMMENT '取消原因',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_schedule_id` (`schedule_id`),
  KEY `idx_status` (`status`),
  KEY `idx_appointment_date` (`appointment_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='挂号订单表';

CREATE TABLE IF NOT EXISTS `t_appointment_idempotency` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '幂等键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `schedule_id` BIGINT NOT NULL COMMENT '排班ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idempotency_key` (`idempotency_key`),
  KEY `idx_user_schedule` (`user_id`, `schedule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='挂号幂等表';

CREATE TABLE IF NOT EXISTS `t_department` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `dept_code` VARCHAR(32) NOT NULL COMMENT '科室编码',
  `dept_name` VARCHAR(64) NOT NULL COMMENT '科室名称',
  `is_hot` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否热门科室 0-否 1-是',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_code` (`dept_code`),
  KEY `idx_is_hot` (`is_hot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='科室表';

CREATE TABLE IF NOT EXISTS `t_doctor` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `doctor_code` VARCHAR(32) NOT NULL COMMENT '医生编码',
  `doctor_name` VARCHAR(64) NOT NULL COMMENT '医生姓名',
  `dept_id` BIGINT NOT NULL COMMENT '科室ID',
  `title` VARCHAR(32) DEFAULT NULL COMMENT '职称',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doctor_code` (`doctor_code`),
  KEY `idx_dept_id` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='医生表';

CREATE TABLE IF NOT EXISTS `t_schedule` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `doctor_id` BIGINT NOT NULL COMMENT '医生ID',
  `dept_id` BIGINT NOT NULL COMMENT '科室ID',
  `schedule_date` DATE NOT NULL COMMENT '排班日期',
  `time_slot` VARCHAR(16) NOT NULL COMMENT '时段: AM-上午 PM-下午',
  `total_quota` INT NOT NULL COMMENT '总号源',
  `available_quota` INT NOT NULL COMMENT '可用号源',
  `fee_cents` INT NOT NULL COMMENT '挂号费(分)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT '状态: OPEN-开放 CLOSED-关闭',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_doctor_date_slot` (`doctor_id`, `schedule_date`, `time_slot`),
  KEY `idx_dept_date` (`dept_id`, `schedule_date`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='排班表';

CREATE TABLE IF NOT EXISTS `t_quota_refund_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `schedule_id` BIGINT NOT NULL COMMENT '排班ID',
  `refund_reason` VARCHAR(255) DEFAULT NULL COMMENT '退号原因',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_no` (`order_no`),
  KEY `idx_schedule_id` (`schedule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='号源退还日志';

CREATE TABLE IF NOT EXISTS `t_id_segment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `biz_type` VARCHAR(64) NOT NULL COMMENT '业务类型',
  `max_id` BIGINT NOT NULL DEFAULT 0 COMMENT '当前最大ID',
  `step` INT NOT NULL DEFAULT 1000 COMMENT '步长',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_type` (`biz_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ID段分配表';

CREATE TABLE IF NOT EXISTS `t_payment_tcc_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `xid` VARCHAR(128) NOT NULL COMMENT 'Seata 全局事务ID',
  `state` VARCHAR(16) NOT NULL COMMENT 'TRIED/COMMITTED/ROLLBACKED',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付TCC事务日志';

-- Seata AT 模式 undo_log 表
CREATE TABLE IF NOT EXISTS `undo_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `branch_id` BIGINT NOT NULL,
  `xid` VARCHAR(100) NOT NULL,
  `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL,
  `log_status` INT NOT NULL,
  `log_created` DATETIME NOT NULL,
  `log_modified` DATETIME NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata AT模式回滚日志';

-- =====================================================
-- 2. payment_db - 支付服务
-- =====================================================
CREATE DATABASE IF NOT EXISTS `payment_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `payment_db`;

-- 支付表 (分片表 t_payment_0, t_payment_1)
CREATE TABLE IF NOT EXISTS `t_payment_0` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `pay_no` VARCHAR(64) NOT NULL COMMENT '支付单号',
  `amount_cents` BIGINT NOT NULL COMMENT '金额(分)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-待支付 PREPARED-预扣款 SUCCEEDED-已支付 CANCELLED-已取消',
  `version` BIGINT NOT NULL DEFAULT 1 COMMENT '支付事实版本',
  `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT '幂等键',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_pay_no` (`pay_no`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付表 - 分片0';

CREATE TABLE IF NOT EXISTS `t_payment_1` LIKE `t_payment_0`;
ALTER TABLE `t_payment_1` COMMENT='支付表 - 分片1';

CREATE TABLE IF NOT EXISTS `t_outbox` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `event_id` VARCHAR(64) NOT NULL COMMENT '事件ID',
  `aggregate_type` VARCHAR(64) NOT NULL COMMENT '聚合类型',
  `aggregate_id` VARCHAR(64) NOT NULL COMMENT '聚合ID',
  `event_type` VARCHAR(100) NOT NULL COMMENT '事件类型',
  `payload` TEXT NOT NULL COMMENT '消息体(JSON)',
  `status` VARCHAR(20) NOT NULL DEFAULT 'NEW' COMMENT '状态',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  `next_retry_at` TIMESTAMP NULL DEFAULT NULL COMMENT '下次重试时间',
  `error_reason` VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `sent_at` TIMESTAMP NULL DEFAULT NULL COMMENT '发送时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event_id` (`event_id`),
  KEY `idx_status_next_retry_at` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付Outbox消息表';

CREATE TABLE IF NOT EXISTS `t_id_segment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `biz_type` VARCHAR(64) NOT NULL COMMENT '业务类型',
  `max_id` BIGINT NOT NULL DEFAULT 0 COMMENT '当前最大ID',
  `step` INT NOT NULL DEFAULT 1000 COMMENT '步长',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_type` (`biz_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ID段分配表';

CREATE TABLE IF NOT EXISTS `undo_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `branch_id` BIGINT NOT NULL,
  `xid` VARCHAR(100) NOT NULL,
  `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL,
  `log_status` INT NOT NULL,
  `log_created` DATETIME NOT NULL,
  `log_modified` DATETIME NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata AT模式回滚日志';

-- =====================================================
-- 3. inventory_db - 库存服务
-- =====================================================
CREATE DATABASE IF NOT EXISTS `inventory_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `inventory_db`;

CREATE TABLE IF NOT EXISTS `t_stock` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `product_id` VARCHAR(64) NOT NULL COMMENT '商品ID',
  `available` INT NOT NULL COMMENT '可用库存',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存表';

CREATE TABLE IF NOT EXISTS `t_inventory_tcc_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `order_no` VARCHAR(64) NOT NULL COMMENT '订单号',
  `product_id` VARCHAR(64) NOT NULL COMMENT '商品ID',
  `quantity` INT NOT NULL COMMENT '冻结/回滚数量',
  `xid` VARCHAR(128) NOT NULL COMMENT 'Seata 全局事务ID',
  `state` VARCHAR(16) NOT NULL COMMENT 'TRIED/COMMITTED/ROLLBACKED',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  KEY `idx_product_id` (`product_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存TCC事务日志';

CREATE TABLE IF NOT EXISTS `undo_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  `branch_id` BIGINT NOT NULL,
  `xid` VARCHAR(100) NOT NULL,
  `context` VARCHAR(128) NOT NULL,
  `rollback_info` LONGBLOB NOT NULL,
  `log_status` INT NOT NULL,
  `log_created` DATETIME NOT NULL,
  `log_modified` DATETIME NOT NULL,
  UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata AT模式回滚日志';

-- =====================================================
-- 4. user_db - 用户服务
-- =====================================================
CREATE DATABASE IF NOT EXISTS `user_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `user_db`;

-- 用户表 (分片表 t_user_0, t_user_1)
CREATE TABLE IF NOT EXISTS `t_user_0` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `username` VARCHAR(64) NOT NULL COMMENT '用户名',
  `display_name` VARCHAR(64) DEFAULT NULL COMMENT '显示名称',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE/DISABLED',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户资料表 - 分片0';

CREATE TABLE IF NOT EXISTS `t_user_1` LIKE `t_user_0`;
ALTER TABLE `t_user_1` COMMENT='用户表 - 分片1';

CREATE TABLE IF NOT EXISTS `t_user_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `balance_cents` BIGINT NOT NULL DEFAULT 0 COMMENT '账户余额(分)',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户账户表';

CREATE TABLE IF NOT EXISTS `t_user_auth_account` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `username` VARCHAR(64) NOT NULL COMMENT '用户名',
  `password_hash` VARCHAR(128) NOT NULL COMMENT '密码哈希',
  `password_salt` VARCHAR(128) NOT NULL COMMENT '密码盐',
  `status` VARCHAR(16) NOT NULL COMMENT 'ACTIVE/DISABLED',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_user_auth_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户认证账户表';

CREATE TABLE IF NOT EXISTS `t_user_admin_role` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `role` VARCHAR(32) NOT NULL COMMENT '后台角色',
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`user_id`, `role`),
  CONSTRAINT `ck_user_admin_role` CHECK (`role` IN ('ORDER_OPERATOR', 'FINANCE_VIEWER', 'INVENTORY_VIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户后台角色表';

CREATE TABLE IF NOT EXISTS `t_id_segment` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `biz_type` VARCHAR(64) NOT NULL COMMENT '业务类型',
  `max_id` BIGINT NOT NULL DEFAULT 0 COMMENT '当前最大ID',
  `step` INT NOT NULL DEFAULT 1000 COMMENT '步长',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_type` (`biz_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ID段分配表';

-- =====================================================
-- 5. seata - Seata Server 事务协调
-- =====================================================
CREATE DATABASE IF NOT EXISTS `seata` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `seata`;

CREATE TABLE IF NOT EXISTS `global_table` (
  `xid` VARCHAR(128) NOT NULL,
  `transaction_id` BIGINT,
  `status` TINYINT NOT NULL,
  `application_id` VARCHAR(32),
  `transaction_service_group` VARCHAR(32),
  `transaction_name` VARCHAR(128),
  `timeout` INT,
  `begin_time` BIGINT,
  `application_data` VARCHAR(2000),
  `gmt_create` DATETIME,
  `gmt_modified` DATETIME,
  PRIMARY KEY (`xid`),
  KEY `idx_gmt_modified_status` (`gmt_modified`, `status`),
  KEY `idx_transaction_id` (`transaction_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata全局事务表';

CREATE TABLE IF NOT EXISTS `branch_table` (
  `branch_id` BIGINT NOT NULL,
  `xid` VARCHAR(128) NOT NULL,
  `transaction_id` BIGINT,
  `resource_group_id` VARCHAR(32),
  `resource_id` VARCHAR(256),
  `branch_type` VARCHAR(8),
  `status` TINYINT,
  `client_id` VARCHAR(64),
  `application_data` VARCHAR(2000),
  `gmt_create` DATETIME(6),
  `gmt_modified` DATETIME(6),
  PRIMARY KEY (`branch_id`),
  KEY `idx_xid` (`xid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata分支事务表';

CREATE TABLE IF NOT EXISTS `lock_table` (
  `row_key` VARCHAR(128) NOT NULL,
  `xid` VARCHAR(96),
  `transaction_id` BIGINT,
  `branch_id` BIGINT NOT NULL,
  `resource_id` VARCHAR(256),
  `table_name` VARCHAR(32),
  `pk` VARCHAR(36),
  `gmt_create` DATETIME,
  `gmt_modified` DATETIME,
  PRIMARY KEY (`row_key`),
  KEY `idx_branch_id` (`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Seata全局锁表';

-- =====================================================
-- 初始化测试数据
-- =====================================================

-- 库存初始化
USE `inventory_db`;
INSERT IGNORE INTO `t_stock` (`product_id`, `available`) VALUES
('p1', 1000),
('p2', 500),
('p3', 800);

-- 科室初始化
USE `order_db`;
INSERT IGNORE INTO `t_department` (`id`, `dept_code`, `dept_name`, `is_hot`) VALUES
(1, 'NEI', '内科', 1),
(2, 'WAI', '外科', 1),
(3, 'FU', '妇科', 0),
(4, 'ER', '儿科', 1);

-- ID段初始化
INSERT IGNORE INTO `t_id_segment` (`biz_type`, `max_id`, `step`) VALUES
('order', 0, 1000),
('appointment', 0, 1000);

USE `payment_db`;
INSERT IGNORE INTO `t_id_segment` (`biz_type`, `max_id`, `step`) VALUES
('payment', 0, 1000);

USE `user_db`;
INSERT IGNORE INTO `t_id_segment` (`biz_type`, `max_id`, `step`) VALUES
('user', 0, 1000);
