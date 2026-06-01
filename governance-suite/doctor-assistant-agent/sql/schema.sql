-- =============================================
-- 医生助手 ReAct Agent 数据库设计
-- =============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS doctor_assistant DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE doctor_assistant;

-- =============================================
-- 1. Agent 会话表
-- =============================================
CREATE TABLE agent_session (
    id VARCHAR(64) PRIMARY KEY COMMENT '会话ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '患者ID',
    doctor_id VARCHAR(64) NOT NULL COMMENT '医生ID',
    goal TEXT NOT NULL COMMENT '任务目标',
    status VARCHAR(16) NOT NULL COMMENT '状态：RUNNING/PAUSED/COMPLETED/FAILED',
    history_json JSON COMMENT '执行历史（Step列表）',
    messages_json JSON COMMENT '对话消息（Message列表）',
    context_json JSON COMMENT '显式状态上下文',
    current_step INT DEFAULT 0 COMMENT '当前步数',
    final_answer TEXT COMMENT '最终答案',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    completed_at DATETIME COMMENT '完成时间',
    INDEX idx_customer (customer_id),
    INDEX idx_doctor (doctor_id),
    INDEX idx_status (status),
    INDEX idx_created (created_at)
) COMMENT='Agent会话表';

-- =============================================
-- 2. 待确认任务表
-- =============================================
CREATE TABLE agent_pending_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '任务ID',
    agent_session_id VARCHAR(64) NOT NULL COMMENT 'Agent会话ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '患者ID',
    doctor_id VARCHAR(64) NOT NULL COMMENT '医生ID',
    tool_name VARCHAR(64) NOT NULL COMMENT '工具名称',
    tool_description VARCHAR(255) COMMENT '工具描述',
    input_json JSON NOT NULL COMMENT '输入参数',
    reason TEXT COMMENT '执行原因',
    status VARCHAR(16) NOT NULL COMMENT '状态：PENDING/APPROVED/REJECTED',
    review_comment TEXT COMMENT '审核意见',
    reviewed_at DATETIME COMMENT '审核时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    expire_at DATETIME COMMENT '过期时间（24小时）',
    INDEX idx_session (agent_session_id),
    INDEX idx_doctor_status (doctor_id, status),
    INDEX idx_expire (expire_at),
    INDEX idx_created (created_at)
) COMMENT='Agent待确认任务表';

-- =============================================
-- 3. Agent 执行日志表
-- =============================================
CREATE TABLE agent_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '日志ID',
    session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    pending_task_id BIGINT COMMENT '待审批任务ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '患者ID',
    doctor_id VARCHAR(64) NOT NULL COMMENT '医生ID',
    goal TEXT COMMENT '任务目标',
    total_steps INT COMMENT '总步数',
    tokens_used INT COMMENT '消耗Token数',
    latency_ms BIGINT COMMENT '总耗时(ms)',
    result_status VARCHAR(16) COMMENT '结果状态',
    final_answer TEXT COMMENT '最终答案',
    tool_name VARCHAR(64) COMMENT '工具名称',
    tool_input_json JSON COMMENT '工具输入参数',
    tool_output_json JSON COMMENT '工具输出结果',
    approval_status VARCHAR(16) COMMENT '审批状态或风险等级',
    review_comment TEXT COMMENT '审批意见',
    reviewed_at DATETIME COMMENT '审批时间',
    error_message TEXT COMMENT '错误信息',
    execution_history_json JSON COMMENT '执行历史',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_session (session_id),
    INDEX idx_pending_task (pending_task_id),
    INDEX idx_customer (customer_id),
    INDEX idx_doctor (doctor_id),
    INDEX idx_result_status (result_status),
    INDEX idx_approval_status (approval_status),
    INDEX idx_created (created_at)
) COMMENT='Agent执行日志表';

-- =============================================
-- 4. 患者档案表（示例数据）
-- =============================================
CREATE TABLE patient_profile (
    customer_id VARCHAR(64) PRIMARY KEY COMMENT '患者ID',
    name VARCHAR(64) NOT NULL COMMENT '姓名',
    age INT COMMENT '年龄',
    gender VARCHAR(8) COMMENT '性别',
    height DECIMAL(5,2) COMMENT '身高(cm)',
    current_weight DECIMAL(5,2) COMMENT '当前体重(kg)',
    target_weight DECIMAL(5,2) COMMENT '目标体重(kg)',
    bmi DECIMAL(5,2) COMMENT 'BMI指数',
    chronic_diseases JSON COMMENT '慢性病列表',
    allergies JSON COMMENT '过敏史',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_name (name)
) COMMENT='患者档案表';

-- =============================================
-- 5. 体重记录表
-- =============================================
CREATE TABLE weight_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '患者ID',
    weight DECIMAL(5,2) NOT NULL COMMENT '体重(kg)',
    bmi DECIMAL(5,2) COMMENT 'BMI指数',
    record_date DATE NOT NULL COMMENT '记录日期',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_customer_date (customer_id, record_date)
) COMMENT='体重记录表';

-- =============================================
-- 6. 聊天记录表
-- =============================================
CREATE TABLE chat_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '消息ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '患者ID',
    role VARCHAR(16) NOT NULL COMMENT '角色：USER/ASSISTANT',
    content TEXT NOT NULL COMMENT '消息内容',
    sentiment VARCHAR(16) COMMENT '情感倾向：POSITIVE/NEUTRAL/NEGATIVE',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_customer_created (customer_id, created_at)
) COMMENT='聊天记录表';

-- =============================================
-- 7. 处方记录表
-- =============================================
CREATE TABLE prescription_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '处方ID',
    customer_id VARCHAR(64) NOT NULL COMMENT '患者ID',
    doctor_id VARCHAR(64) NOT NULL COMMENT '医生ID',
    prescription_type VARCHAR(32) COMMENT '处方类型',
    items_json JSON NOT NULL COMMENT '处方项目列表',
    status VARCHAR(16) NOT NULL COMMENT '状态：DRAFT/ACTIVE/COMPLETED',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_customer (customer_id),
    INDEX idx_doctor (doctor_id),
    INDEX idx_created (created_at)
) COMMENT='处方记录表';

-- =============================================
-- 插入测试数据
-- =============================================

-- 患者档案
INSERT INTO patient_profile (customer_id, name, age, gender, height, current_weight, target_weight, bmi, chronic_diseases, allergies) VALUES
('C001', '张三', 35, '女', 165.0, 62.0, 55.0, 22.8, '["高血压"]', '[]'),
('C002', '李四', 42, '男', 175.0, 85.0, 75.0, 27.8, '["糖尿病", "高血脂"]', '["青霉素"]'),
('C003', '王五', 28, '女', 160.0, 58.0, 52.0, 22.7, '[]', '[]');

-- 体重记录（最近30天）
INSERT INTO weight_record (customer_id, weight, bmi, record_date) VALUES
('C001', 65.0, 23.9, DATE_SUB(CURDATE(), INTERVAL 30 DAY)),
('C001', 64.5, 23.7, DATE_SUB(CURDATE(), INTERVAL 25 DAY)),
('C001', 63.8, 23.4, DATE_SUB(CURDATE(), INTERVAL 20 DAY)),
('C001', 63.0, 23.1, DATE_SUB(CURDATE(), INTERVAL 15 DAY)),
('C001', 62.5, 22.9, DATE_SUB(CURDATE(), INTERVAL 10 DAY)),
('C001', 62.2, 22.8, DATE_SUB(CURDATE(), INTERVAL 5 DAY)),
('C001', 63.0, 23.1, DATE_SUB(CURDATE(), INTERVAL 2 DAY)),
('C001', 62.0, 22.8, CURDATE());

-- 聊天记录
INSERT INTO chat_message (customer_id, role, content, sentiment) VALUES
('C001', 'USER', '最近感觉有点焦虑，是不是方法不对？', 'NEGATIVE'),
('C001', 'ASSISTANT', '焦虑是正常的，我们一起来看看进展如何。', 'POSITIVE'),
('C001', 'USER', '体重好像又反弹了', 'NEGATIVE'),
('C001', 'ASSISTANT', '短期波动是正常的，重要的是整体趋势。', 'NEUTRAL');

-- 处方记录
INSERT INTO prescription_record (customer_id, doctor_id, prescription_type, items_json, status) VALUES
('C001', 'D001', '药物处方', '[{"name": "降压药", "dosage": "5mg", "frequency": "每日一次"}]', 'ACTIVE');
