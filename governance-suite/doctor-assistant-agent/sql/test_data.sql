-- 端到端测试数据
-- 用于测试完整的 Agent 执行流程

USE doctor_assistant;

-- 清空现有数据
TRUNCATE TABLE agent_session;
TRUNCATE TABLE agent_pending_task;
TRUNCATE TABLE agent_execution_log;
TRUNCATE TABLE patient_profile;
TRUNCATE TABLE weight_record;
TRUNCATE TABLE chat_message;
TRUNCATE TABLE prescription_record;

-- 插入测试患者数据
INSERT INTO patient_profile (customer_id, name, age, gender, height, current_weight, target_weight, chronic_diseases, bmi, created_at, updated_at)
VALUES
('C001', '张三', 35, '女', 165, 62.0, 55.0, '高血压', 22.8, NOW(), NOW()),
('C002', '李四', 42, '男', 175, 85.0, 75.0, '糖尿病,高血脂', 27.8, NOW(), NOW()),
('C003', '王五', 28, '女', 160, 58.0, 52.0, NULL, 22.7, NOW(), NOW());

-- 插入体重记录（最近30天）
INSERT INTO weight_record (customer_id, weight, bmi, record_date, created_at)
VALUES
-- 张三的体重记录（减重进展良好）
('C001', 65.0, 23.9, DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY)),
('C001', 64.5, 23.7, DATE_SUB(NOW(), INTERVAL 25 DAY), DATE_SUB(NOW(), INTERVAL 25 DAY)),
('C001', 64.0, 23.5, DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
('C001', 63.5, 23.3, DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY)),
('C001', 63.0, 23.1, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY)),
('C001', 62.5, 23.0, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY)),
('C001', 62.0, 22.8, NOW(), NOW()),

-- 李四的体重记录（减重缓慢）
('C002', 87.0, 28.4, DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY)),
('C002', 86.5, 28.2, DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
('C002', 86.0, 28.1, DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY)),
('C002', 85.0, 27.8, NOW(), NOW()),

-- 王五的体重记录（稳定）
('C003', 58.5, 22.9, DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY)),
('C003', 58.2, 22.8, DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY)),
('C003', 58.0, 22.7, NOW(), NOW());

-- 插入聊天记录
INSERT INTO chat_message (customer_id, message, sender, created_at)
VALUES
-- 张三的聊天记录
('C001', '最近感觉有点焦虑，睡眠质量不太好', 'customer', DATE_SUB(NOW(), INTERVAL 3 DAY)),
('C001', '建议您保持规律作息，必要时可以进行心理咨询', 'doctor', DATE_SUB(NOW(), INTERVAL 3 DAY)),
('C001', '体重下降了3kg，感觉很有成就感', 'customer', DATE_SUB(NOW(), INTERVAL 7 DAY)),
('C001', '进展很好，继续保持', 'doctor', DATE_SUB(NOW(), INTERVAL 7 DAY)),
('C001', '血压最近控制得不错，120/80', 'customer', DATE_SUB(NOW(), INTERVAL 10 DAY)),

-- 李四的聊天记录
('C002', '最近血糖有点高，空腹7.5', 'customer', DATE_SUB(NOW(), INTERVAL 2 DAY)),
('C002', '需要调整饮食，减少碳水化合物摄入', 'doctor', DATE_SUB(NOW(), INTERVAL 2 DAY)),
('C002', '体重减得很慢，有点沮丧', 'customer', DATE_SUB(NOW(), INTERVAL 5 DAY)),
('C002', '减重需要耐心，建议增加运动量', 'doctor', DATE_SUB(NOW(), INTERVAL 5 DAY)),

-- 王五的聊天记录
('C003', '想咨询一下减重计划', 'customer', DATE_SUB(NOW(), INTERVAL 1 DAY)),
('C003', '您的BMI在正常范围，建议适度减重', 'doctor', DATE_SUB(NOW(), INTERVAL 1 DAY));

-- 插入处方记录
INSERT INTO prescription_record (customer_id, medication, dosage, frequency, duration_days, notes, prescribed_at, created_at)
VALUES
-- 张三的处方
('C001', '厄贝沙坦片', '150mg', '每日1次', 30, '用于控制高血压', DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY)),
('C001', '阿司匹林肠溶片', '100mg', '每日1次', 30, '预防心血管疾病', DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY)),

-- 李四的处方
('C002', '二甲双胍片', '500mg', '每日2次', 30, '控制血糖', DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
('C002', '阿托伐他汀钙片', '20mg', '每日1次', 30, '降低血脂', DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY));

-- 验证数据
SELECT '患者档案' AS table_name, COUNT(*) AS count FROM patient_profile
UNION ALL
SELECT '体重记录', COUNT(*) FROM weight_record
UNION ALL
SELECT '聊天记录', COUNT(*) FROM chat_message
UNION ALL
SELECT '处方记录', COUNT(*) FROM prescription_record;
