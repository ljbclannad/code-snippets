USE governance;
ALTER TABLE t_ai_chat_history MODIFY conversation_id VARCHAR(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID';

CREATE TABLE IF NOT EXISTS ai_chat_conversation_owner (
    conversation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    customer_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '历史归属冲突时为空，拒绝访问'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 切换前停止旧入口写入。随访表需先完成高风险随访迁移。
INSERT INTO ai_chat_conversation_owner (conversation_id, customer_id)
SELECT conversation_id, IF(COUNT(DISTINCT customer_id) = 1, MIN(customer_id), NULL)
FROM (
    SELECT conversation_id COLLATE utf8mb4_bin AS conversation_id, customer_id COLLATE utf8mb4_bin AS customer_id
    FROM t_ai_chat_history
    UNION ALL
    SELECT conversation_id COLLATE utf8mb4_bin, customer_id COLLATE utf8mb4_bin
    FROM doctor_assistant.agent_session WHERE conversation_id IS NOT NULL
    UNION ALL
    SELECT conversation_id, customer_id FROM doctor_assistant.agent_follow_up_reminder
) history
GROUP BY conversation_id
ON DUPLICATE KEY UPDATE customer_id = IF(ai_chat_conversation_owner.customer_id = VALUES(customer_id), VALUES(customer_id), NULL);
