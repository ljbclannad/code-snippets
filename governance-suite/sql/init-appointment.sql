-- =====================================================
-- 挂号场景数据库初始化脚本
-- 创建时间: 2026-05-26
-- 说明: 创建挂号场景所需的6张表及测试数据
-- =====================================================

USE order_db;

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================
-- Step 2: 科室表
-- =====================================================
DROP TABLE IF EXISTS t_department;
CREATE TABLE t_department (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    dept_code VARCHAR(32) NOT NULL UNIQUE COMMENT '科室编码',
    dept_name VARCHAR(64) NOT NULL COMMENT '科室名称',
    is_hot TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否热门科室 0-否 1-是',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_dept_code (dept_code),
    INDEX idx_is_hot (is_hot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='科室表';

-- =====================================================
-- Step 3: 医生表
-- =====================================================
DROP TABLE IF EXISTS t_doctor;
CREATE TABLE t_doctor (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    doctor_code VARCHAR(32) NOT NULL UNIQUE COMMENT '医生编码',
    doctor_name VARCHAR(64) NOT NULL COMMENT '医生姓名',
    dept_id BIGINT NOT NULL COMMENT '所属科室ID',
    title VARCHAR(32) NOT NULL COMMENT '职称: 主任医师/副主任医师/主治医师',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_doctor_code (doctor_code),
    INDEX idx_dept_id (dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='医生表';

-- =====================================================
-- Step 4: 排班表
-- =====================================================
DROP TABLE IF EXISTS t_schedule;
CREATE TABLE t_schedule (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    doctor_id BIGINT NOT NULL COMMENT '医生ID',
    dept_id BIGINT NOT NULL COMMENT '科室ID',
    schedule_date DATE NOT NULL COMMENT '排班日期',
    time_slot VARCHAR(16) NOT NULL COMMENT '时段: morning/afternoon/evening',
    total_quota INT NOT NULL COMMENT '总号源数',
    available_quota INT NOT NULL COMMENT '剩余号源数',
    fee_cents INT NOT NULL COMMENT '挂号费(分)',
    status VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' COMMENT '状态: AVAILABLE-可预约 FULL-已满 CLOSED-已关闭',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次更新+1',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_doctor_date_slot (doctor_id, schedule_date, time_slot),
    INDEX idx_dept_date (dept_id, schedule_date),
    INDEX idx_date_slot (schedule_date, time_slot),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排班表。注意：1) 生产环境ID应使用Snowflake生成 2) 更新available_quota时必须使用乐观锁(WHERE version=?)';

-- =====================================================
-- Step 5: 挂号订单表
-- =====================================================
DROP TABLE IF EXISTS t_appointment_order;
CREATE TABLE t_appointment_order (
    id BIGINT PRIMARY KEY COMMENT '主键ID',
    order_no VARCHAR(64) NOT NULL UNIQUE COMMENT '订单号',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    schedule_id BIGINT NOT NULL COMMENT '排班ID',
    dept_id BIGINT NOT NULL COMMENT '科室ID',
    doctor_id BIGINT NOT NULL COMMENT '医生ID',
    appointment_date DATE NOT NULL COMMENT '就诊日期',
    time_slot VARCHAR(16) NOT NULL COMMENT '就诊时段',
    fee_cents INT NOT NULL COMMENT '挂号费(分)',
    status VARCHAR(16) NOT NULL DEFAULT 'RESERVED' COMMENT '状态: RESERVED-已预约 PAID-已支付 CANCELLED-已取消 EXPIRED-已过期',
    reserved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '预约时间',
    paid_at TIMESTAMP NULL COMMENT '支付时间',
    cancelled_at TIMESTAMP NULL COMMENT '取消时间',
    cancel_reason VARCHAR(255) NULL COMMENT '取消原因',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号，每次更新+1',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_order_no (order_no),
    INDEX idx_user_id (user_id),
    INDEX idx_schedule_id (schedule_id),
    INDEX idx_status (status),
    INDEX idx_appointment_date (appointment_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号订单表。注意：1) 生产环境ID应使用Snowflake生成 2) 更新status时必须使用乐观锁(WHERE version=?)';

-- =====================================================
-- Step 6: 幂等表
-- =====================================================
DROP TABLE IF EXISTS t_appointment_idempotency;
CREATE TABLE t_appointment_idempotency (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    idempotency_key VARCHAR(128) NOT NULL UNIQUE COMMENT '幂等键',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    schedule_id BIGINT NOT NULL COMMENT '排班ID',
    order_no VARCHAR(64) NOT NULL COMMENT '订单号',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_schedule (user_id, schedule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='挂号幂等表';

-- =====================================================
-- Step 7: 库存回补日志表
-- =====================================================
DROP TABLE IF EXISTS t_quota_refund_log;
CREATE TABLE t_quota_refund_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    schedule_id BIGINT NOT NULL COMMENT '排班ID',
    order_no VARCHAR(64) NOT NULL COMMENT '订单号',
    refund_type VARCHAR(32) NOT NULL COMMENT '回补类型: CANCEL-取消订单 EXPIRE-订单过期',
    refunded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '回补时间',
    INDEX idx_schedule_id (schedule_id),
    INDEX idx_order_no (order_no),
    INDEX idx_refunded_at (refunded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号源回补日志表';

-- =====================================================
-- Step 8: 插入科室测试数据
-- =====================================================
INSERT INTO t_department (id, dept_code, dept_name, is_hot) VALUES
(1001, 'DEPT_INTERNAL', '内科', 0),
(1002, 'DEPT_SURGERY', '外科', 0),
(1003, 'DEPT_PEDIATRICS', '儿科', 1),
(1004, 'DEPT_GYNECOLOGY', '妇科', 0),
(1005, 'DEPT_ORTHOPEDICS', '骨科', 0);

-- =====================================================
-- Step 9: 插入医生测试数据
-- =====================================================
INSERT INTO t_doctor (id, doctor_code, doctor_name, dept_id, title) VALUES
-- 内科医生
(2001, 'DOC_2001', '张主任', 1001, '主任医师'),
(2002, 'DOC_2002', '李医生', 1001, '副主任医师'),
-- 外科医生
(2003, 'DOC_2003', '王主任', 1002, '主任医师'),
(2004, 'DOC_2004', '赵医生', 1002, '主治医师'),
-- 儿科医生（热门科室）
(2005, 'DOC_2005', '刘主任', 1003, '主任医师'),
(2006, 'DOC_2006', '陈医生', 1003, '副主任医师'),
(2007, 'DOC_2007', '杨医生', 1003, '主治医师'),
-- 妇科医生
(2008, 'DOC_2008', '周主任', 1004, '主任医师'),
(2009, 'DOC_2009', '吴医生', 1004, '副主任医师'),
-- 骨科医生
(2010, 'DOC_2010', '郑主任', 1005, '主任医师'),
(2011, 'DOC_2011', '孙医生', 1005, '主治医师');

-- =====================================================
-- Step 10: 生成排班测试数据（存储过程）
-- =====================================================
DROP PROCEDURE IF EXISTS generate_schedules;

DELIMITER $$
CREATE PROCEDURE generate_schedules()
BEGIN
    DECLARE v_doctor_id BIGINT;
    DECLARE v_dept_id BIGINT;
    DECLARE v_title VARCHAR(32);
    DECLARE v_is_hot TINYINT(1);
    DECLARE v_date DATE;
    DECLARE v_day_offset INT;
    DECLARE v_time_slot VARCHAR(16);
    DECLARE v_fee_cents INT;
    DECLARE v_total_quota INT;
    DECLARE v_schedule_id BIGINT;
    DECLARE done INT DEFAULT FALSE;

    -- 游标：遍历所有医生
    DECLARE doctor_cursor CURSOR FOR
        SELECT d.id, d.dept_id, d.title, dept.is_hot
        FROM t_doctor d
        JOIN t_department dept ON d.dept_id = dept.id;

    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    -- 清空现有排班数据
    TRUNCATE TABLE t_schedule;

    OPEN doctor_cursor;

    doctor_loop: LOOP
        FETCH doctor_cursor INTO v_doctor_id, v_dept_id, v_title, v_is_hot;
        IF done THEN
            LEAVE doctor_loop;
        END IF;

        -- 根据职称确定挂号费
        SET v_fee_cents = CASE v_title
            WHEN '主任医师' THEN 500
            WHEN '副主任医师' THEN 300
            WHEN '主治医师' THEN 200
            ELSE 200
        END;

        -- 根据是否热门科室确定号源数
        SET v_total_quota = IF(v_is_hot = 1, 20, 50);

        -- 生成未来7天的排班
        SET v_day_offset = 0;
        WHILE v_day_offset < 7 DO
            SET v_date = DATE_ADD(CURDATE(), INTERVAL v_day_offset DAY);

            -- 生成3个时段的排班
            SET v_time_slot = 'morning';
            SET v_schedule_id = v_doctor_id * 1000000 + v_day_offset * 1000 + 1;
            INSERT INTO t_schedule (id, doctor_id, dept_id, schedule_date, time_slot, total_quota, available_quota, fee_cents, status)
            VALUES (v_schedule_id, v_doctor_id, v_dept_id, v_date, v_time_slot, v_total_quota, v_total_quota, v_fee_cents, 'AVAILABLE');

            SET v_time_slot = 'afternoon';
            SET v_schedule_id = v_doctor_id * 1000000 + v_day_offset * 1000 + 2;
            INSERT INTO t_schedule (id, doctor_id, dept_id, schedule_date, time_slot, total_quota, available_quota, fee_cents, status)
            VALUES (v_schedule_id, v_doctor_id, v_dept_id, v_date, v_time_slot, v_total_quota, v_total_quota, v_fee_cents, 'AVAILABLE');

            SET v_time_slot = 'evening';
            SET v_schedule_id = v_doctor_id * 1000000 + v_day_offset * 1000 + 3;
            INSERT INTO t_schedule (id, doctor_id, dept_id, schedule_date, time_slot, total_quota, available_quota, fee_cents, status)
            VALUES (v_schedule_id, v_doctor_id, v_dept_id, v_date, v_time_slot, v_total_quota, v_total_quota, v_fee_cents, 'AVAILABLE');

            SET v_day_offset = v_day_offset + 1;
        END WHILE;
    END LOOP;

    CLOSE doctor_cursor;
END$$
DELIMITER ;

-- 执行存储过程生成排班数据
CALL generate_schedules();

-- =====================================================
-- Step 11: 验证查询
-- =====================================================
SELECT '========== 科室统计 ==========' AS '';
SELECT
    dept_code AS '科室编码',
    dept_name AS '科室名称',
    CASE is_hot WHEN 1 THEN '是' ELSE '否' END AS '是否热门',
    (SELECT COUNT(*) FROM t_doctor WHERE dept_id = d.id) AS '医生数量'
FROM t_department d
ORDER BY id;

SELECT '========== 医生统计 ==========' AS '';
SELECT
    d.doctor_code AS '医生编码',
    d.doctor_name AS '医生姓名',
    dept.dept_name AS '所属科室',
    d.title AS '职称',
    (SELECT COUNT(*) FROM t_schedule WHERE doctor_id = d.id) AS '排班数量'
FROM t_doctor d
JOIN t_department dept ON d.dept_id = dept.id
ORDER BY d.id;

SELECT '========== 排班统计 ==========' AS '';
SELECT
    dept.dept_name AS '科室',
    COUNT(*) AS '排班总数',
    SUM(total_quota) AS '总号源数',
    SUM(available_quota) AS '剩余号源数'
FROM t_schedule s
JOIN t_department dept ON s.dept_id = dept.id
GROUP BY dept.dept_name, dept.id
ORDER BY dept.id;

SELECT '========== 总体统计 ==========' AS '';
SELECT
    (SELECT COUNT(*) FROM t_department) AS '科室总数',
    (SELECT COUNT(*) FROM t_doctor) AS '医生总数',
    (SELECT COUNT(*) FROM t_schedule) AS '排班总数',
    (SELECT SUM(total_quota) FROM t_schedule) AS '总号源数';

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================
-- 初始化完成
-- =====================================================
SELECT '========== 初始化完成 ==========' AS '';
SELECT 'Database initialization completed successfully!' AS 'Status';
