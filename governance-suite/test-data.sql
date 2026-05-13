-- =============================================
-- AI 记忆功能测试数据
-- 创建时间: 2026-05-13
-- =============================================

USE governance;

-- 清理已存在的测试数据
DELETE FROM b_ai_user_memory WHERE customer_id = 'test-customer-001';
DELETE FROM b_ai_group_memory WHERE customer_id = 'test-customer-001';
DELETE FROM b_ai_chat_history WHERE customer_id = 'test-customer-001';

-- 1. 插入测试用户的全局记忆（2条）
-- 记忆1: 饮食偏好
INSERT INTO b_ai_user_memory
  (customer_id, memory_type, memory_key, memory_content, confidence_score, source, verified, verified_by, verified_at)
VALUES
  ('test-customer-001', 'DIET_RESTRICTION', '低盐饮食', '张三偏好低盐饮食，每日盐摄入量控制在5克以内', 0.950, 'CHAT', 1, 'system', NOW());

-- 记忆2: 过敏信息
INSERT INTO b_ai_user_memory
  (customer_id, memory_type, memory_key, memory_content, confidence_score, source, verified, verified_by, verified_at)
VALUES
  ('test-customer-001', 'ALLERGY', '海鲜过敏', '张三对虾、蟹等甲壳类海鲜过敏，食用后会出现皮疹', 1.000, 'MANUAL', 1, 'doctor-001', NOW());

-- 2. 插入健康评估数据（作为聊天历史记录）
INSERT INTO b_ai_chat_history
  (customer_id, group_id, conversation_id, user_message, ai_response, role, trace_id, response_time)
VALUES
  (
    'test-customer-001',
    'health-assessment-group',
    'conv-test-001',
    '我叫张三，今年35岁，身高175cm，体重75kg，血压120/80mmHg，空腹血糖5.2mmol/L',
    '您好张三，根据您提供的信息：\n- BMI: 24.5（正常范围）\n- 血压: 120/80mmHg（正常）\n- 空腹血糖: 5.2mmol/L（正常）\n您的各项健康指标都在正常范围内，请继续保持良好的生活习惯。',
    'doctor',
    'trace-test-001',
    1250
  );

-- 3. 插入额外的群组记忆（用于测试群组记忆功能）
INSERT INTO b_ai_group_memory
  (customer_id, group_id, memory_type, memory_key, memory_content, confidence_score, source_message, extracted_at)
VALUES
  (
    'test-customer-001',
    'health-assessment-group',
    'SYMPTOM',
    '偶尔头痛',
    '张三提到偶尔会有头痛症状，通常在工作压力大时出现',
    0.850,
    '最近工作压力比较大，偶尔会头痛',
    NOW()
  );

COMMIT;

-- 验证数据插入
SELECT '=== 用户全局记忆 ===' AS '';
SELECT customer_id, memory_type, memory_key, memory_content, confidence_score, verified
FROM b_ai_user_memory
WHERE customer_id = 'test-customer-001';

SELECT '=== 聊天历史（健康评估）===' AS '';
SELECT customer_id, group_id, LEFT(user_message, 50) AS user_message_preview, role, response_time
FROM b_ai_chat_history
WHERE customer_id = 'test-customer-001';

SELECT '=== 群组记忆 ===' AS '';
SELECT customer_id, group_id, memory_type, memory_key, memory_content, confidence_score
FROM b_ai_group_memory
WHERE customer_id = 'test-customer-001';
