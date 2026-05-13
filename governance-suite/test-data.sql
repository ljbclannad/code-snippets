-- 清空现有测试数据
DELETE FROM b_ai_user_memory WHERE customer_id IN ('test-customer-001', 'test-customer-002', 'user123');
DELETE FROM b_ai_group_memory;
DELETE FROM b_ai_chat_history;

-- 为 test-customer-001 创建 2 条全局记忆（符合规范要求）
INSERT INTO b_ai_user_memory (customer_id, memory_type, memory_key, memory_content, confidence_score, source, verified, create_time, update_time, is_deleted)
VALUES
('test-customer-001', 'preference', 'diet', '喜欢清淡饮食，不吃辣', 0.95, 'chat', 1, NOW(), NOW(), 0),
('test-customer-001', 'health_condition', 'allergy', '对海鲜过敏', 0.98, 'chat', 1, NOW(), NOW(), 0);
