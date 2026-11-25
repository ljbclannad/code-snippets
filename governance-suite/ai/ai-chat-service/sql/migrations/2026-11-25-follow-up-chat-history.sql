-- 在 governance 数据库的 t_ai_chat_history 上执行一次；先迁移，再部署聊天服务。
-- 普通对话的 follow_up_key 为 NULL，不受此唯一索引约束。
USE governance;
ALTER TABLE t_ai_chat_history
    ADD COLUMN follow_up_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '随访问答请求去重键',
    ADD COLUMN follow_up_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '是否已确认远端结果',
    ADD COLUMN follow_up_received_at BIGINT NULL COMMENT '随访问答首次接收时间',
    ADD UNIQUE KEY uk_follow_up_turn (follow_up_key);
