ALTER TABLE b_ai_group_memory
    ADD COLUMN memory_representation VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE' COMMENT '记忆表现形态: EVIDENCE/SUMMARY' AFTER memory_content,
    ADD COLUMN summary_group_key VARCHAR(128) NULL COMMENT '摘要分组键' AFTER memory_representation,
    ADD COLUMN summary_of_memory_id BIGINT NULL COMMENT '摘要关联的主证据记忆ID' AFTER summary_group_key;
