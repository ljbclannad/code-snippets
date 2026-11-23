USE doctor_assistant;
ALTER TABLE agent_session ADD COLUMN conversation_id VARCHAR(128) NULL COMMENT '患者侧会话编号',
    ADD INDEX idx_review_conversation (customer_id, conversation_id);

UPDATE agent_session s
JOIN agent_pending_task t ON t.agent_session_id = s.id
JOIN agent_follow_up_reminder r ON r.pending_task_id = t.id
SET s.conversation_id = r.conversation_id
WHERE BINARY s.customer_id = BINARY r.customer_id AND BINARY t.customer_id = BINARY r.customer_id;

UPDATE agent_session
SET conversation_id = JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.slots.patient_task_request.value.conversationId'))
WHERE conversation_id IS NULL AND JSON_VALID(context_json)
  AND JSON_TYPE(JSON_EXTRACT(context_json, '$.slots.patient_task_request.value.conversationId')) = 'STRING';
