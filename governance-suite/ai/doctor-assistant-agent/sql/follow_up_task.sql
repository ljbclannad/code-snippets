-- 随访任务表
CREATE TABLE IF NOT EXISTS follow_up_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    task_id VARCHAR(64) NOT NULL UNIQUE COMMENT '任务编号',
    customer_id VARCHAR(64) NOT NULL COMMENT '患者ID',
    task_description TEXT NOT NULL COMMENT '任务描述',
    due_date DATE NOT NULL COMMENT '截止日期',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-待处理, COMPLETED-已完成, CANCELLED-已取消',
    created_by VARCHAR(50) NOT NULL COMMENT '创建者: AGENT-AI助手, DOCTOR-医生',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    completed_at TIMESTAMP NULL COMMENT '完成时间',
    notes TEXT COMMENT '备注',
    INDEX idx_customer_id (customer_id),
    INDEX idx_status (status),
    INDEX idx_due_date (due_date),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='随访任务表';

-- 插入测试数据
INSERT INTO follow_up_task (task_id, customer_id, task_description, due_date, status, created_by, notes) VALUES
('FT001', 'C001', '复查血压，确认降压药效果', '2026-05-25', 'PENDING', 'AGENT', '患者血压偏高，需要定期监测'),
('FT002', 'C001', '体重监测，目标减重2kg', '2026-06-01', 'PENDING', 'AGENT', '患者体重超标，建议控制饮食'),
('FT003', 'C002', '血糖检测，空腹血糖和餐后血糖', '2026-05-22', 'PENDING', 'DOCTOR', '糖尿病患者，需要密切监测血糖'),
('FT004', 'C003', '复查肝功能，评估用药安全性', '2026-05-28', 'PENDING', 'AGENT', '患者长期服药，需要定期检查肝功能'),
('FT005', 'C001', '营养咨询，制定饮食计划', '2026-05-20', 'COMPLETED', 'AGENT', '已完成营养咨询，患者表示满意'),
('FT006', 'C004', '心理评估，关注情绪状态', '2026-05-30', 'PENDING', 'DOCTOR', '患者近期情绪低落，需要心理干预'),
('FT007', 'C005', '运动指导，制定运动计划', '2026-06-05', 'PENDING', 'AGENT', '患者缺乏运动，建议增加有氧运动'),
('FT008', 'C002', '用药依从性评估', '2026-05-26', 'PENDING', 'AGENT', '患者经常忘记服药，需要提醒'),
('FT009', 'C003', '睡眠质量评估', '2026-05-24', 'CANCELLED', 'AGENT', '患者睡眠问题已改善，取消评估'),
('FT010', 'C004', '复查血脂，评估心血管风险', '2026-06-10', 'PENDING', 'DOCTOR', '患者血脂偏高，需要定期监测');
