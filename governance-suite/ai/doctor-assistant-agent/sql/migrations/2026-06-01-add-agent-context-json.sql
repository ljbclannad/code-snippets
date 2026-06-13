ALTER TABLE agent_session
    ADD COLUMN context_json JSON NULL COMMENT '显式状态上下文' AFTER messages_json;
