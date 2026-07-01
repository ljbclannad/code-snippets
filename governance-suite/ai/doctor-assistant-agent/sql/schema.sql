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
-- 3.1 Agent 运行策略快照表
-- =============================================
CREATE TABLE agent_run_strategy_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    run_id VARCHAR(64) NOT NULL COMMENT '平台runId/会话ID',
    session_id VARCHAR(64) COMMENT 'Agent会话ID',
    scene VARCHAR(64) NOT NULL COMMENT '场景标识',
    task_type VARCHAR(64) COMMENT '任务类型',
    prompt_version VARCHAR(64) COMMENT 'Prompt版本',
    selector_version VARCHAR(64) COMMENT 'Selector版本',
    selected_agent_ids_json JSON COMMENT '选中的子Agent列表',
    selection_mode VARCHAR(16) COMMENT '选择模式：SERIAL/PARALLEL',
    tool_catalog_version VARCHAR(64) COMMENT '工具目录版本',
    tool_sequence_json JSON COMMENT '工具调用顺序',
    memory_profile_json JSON COMMENT '记忆摘要',
    memory_retrieval_count INT COMMENT '记忆召回条数',
    memory_rerank_applied TINYINT(1) COMMENT '是否应用rerank',
    degradation_level VARCHAR(16) COMMENT '降级等级',
    parallel_enabled TINYINT(1) COMMENT '是否启用并行',
    fallback_allowed TINYINT(1) COMMENT '是否允许回退',
    timeout_millis INT COMMENT '超时时间(ms)',
    patient_visible_only TINYINT(1) COMMENT '是否仅患者可见安全回复',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_run_id (run_id),
    INDEX idx_scene_task_created (scene, task_type, created_at),
    INDEX idx_created_at (created_at)
) COMMENT='Agent运行策略快照表';

-- =============================================
-- 3.2 Agent 运行结果表
-- =============================================
CREATE TABLE agent_run_outcome (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    run_id VARCHAR(64) NOT NULL COMMENT '平台runId/会话ID',
    session_id VARCHAR(64) COMMENT 'Agent会话ID',
    scene VARCHAR(64) NOT NULL COMMENT '场景标识',
    task_type VARCHAR(64) COMMENT '任务类型',
    customer_id VARCHAR(64) COMMENT '患者ID',
    doctor_id VARCHAR(64) COMMENT '医生ID',
    final_status VARCHAR(32) NOT NULL COMMENT '最终运行状态',
    outcome_label VARCHAR(64) NOT NULL COMMENT '结构化结果标签',
    fallback_used TINYINT(1) DEFAULT 0 COMMENT '是否使用回退',
    manual_review_required TINYINT(1) DEFAULT 0 COMMENT '是否要求人工复核',
    manual_override TINYINT(1) DEFAULT 0 COMMENT '是否人工覆盖',
    doctor_accepted TINYINT(1) COMMENT '医生是否接受',
    doctor_rejected TINYINT(1) COMMENT '医生是否拒绝',
    evidence_sufficient TINYINT(1) COMMENT '证据是否充分',
    tool_error_count INT DEFAULT 0 COMMENT '工具错误次数',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    duration_ms BIGINT COMMENT '执行耗时(ms)',
    error_code VARCHAR(64) COMMENT '错误码',
    error_message VARCHAR(512) COMMENT '错误信息',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_run_id (run_id),
    INDEX idx_scene_task_created (scene, task_type, created_at),
    INDEX idx_outcome_label_created (outcome_label, created_at)
) COMMENT='Agent运行结果表';

-- =============================================
-- 3.3 Agent 结果归因表
-- =============================================
CREATE TABLE agent_outcome_attribution (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    run_id VARCHAR(64) NOT NULL COMMENT '平台runId/会话ID',
    session_id VARCHAR(64) COMMENT 'Agent会话ID',
    scene VARCHAR(64) NOT NULL COMMENT '场景标识',
    task_type VARCHAR(64) COMMENT '任务类型',
    primary_cause VARCHAR(64) NOT NULL COMMENT '主归因原因',
    secondary_causes_json JSON COMMENT '次级原因列表',
    confidence DECIMAL(5,4) COMMENT '归因置信度',
    evidence_summary_json JSON COMMENT '证据摘要',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_attribution_run_id (run_id),
    INDEX idx_attribution_scene_task_created (scene, task_type, created_at),
    INDEX idx_primary_cause_created (primary_cause, created_at)
) COMMENT='Agent结果归因表';

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
