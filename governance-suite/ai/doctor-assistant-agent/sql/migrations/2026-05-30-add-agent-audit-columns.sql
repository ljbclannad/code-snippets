USE doctor_assistant;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND COLUMN_NAME = 'pending_task_id'
    ),
    'SELECT 1',
    'ALTER TABLE agent_execution_log ADD COLUMN pending_task_id BIGINT COMMENT ''待审批任务ID'' AFTER session_id'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND COLUMN_NAME = 'tool_name'
    ),
    'SELECT 1',
    'ALTER TABLE agent_execution_log ADD COLUMN tool_name VARCHAR(64) COMMENT ''工具名称'' AFTER final_answer'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND COLUMN_NAME = 'tool_input_json'
    ),
    'SELECT 1',
    'ALTER TABLE agent_execution_log ADD COLUMN tool_input_json JSON COMMENT ''工具输入参数'' AFTER tool_name'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND COLUMN_NAME = 'tool_output_json'
    ),
    'SELECT 1',
    'ALTER TABLE agent_execution_log ADD COLUMN tool_output_json JSON COMMENT ''工具输出结果'' AFTER tool_input_json'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND COLUMN_NAME = 'approval_status'
    ),
    'SELECT 1',
    'ALTER TABLE agent_execution_log ADD COLUMN approval_status VARCHAR(16) COMMENT ''审批状态或风险等级'' AFTER tool_output_json'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND COLUMN_NAME = 'review_comment'
    ),
    'SELECT 1',
    'ALTER TABLE agent_execution_log ADD COLUMN review_comment TEXT COMMENT ''审批意见'' AFTER approval_status'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND COLUMN_NAME = 'reviewed_at'
    ),
    'SELECT 1',
    'ALTER TABLE agent_execution_log ADD COLUMN reviewed_at DATETIME COMMENT ''审批时间'' AFTER review_comment'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND INDEX_NAME = 'idx_pending_task'
    ),
    'SELECT 1',
    'CREATE INDEX idx_pending_task ON agent_execution_log (pending_task_id)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND INDEX_NAME = 'idx_result_status'
    ),
    'SELECT 1',
    'CREATE INDEX idx_result_status ON agent_execution_log (result_status)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = 'doctor_assistant'
          AND TABLE_NAME = 'agent_execution_log'
          AND INDEX_NAME = 'idx_approval_status'
    ),
    'SELECT 1',
    'CREATE INDEX idx_approval_status ON agent_execution_log (approval_status)'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
