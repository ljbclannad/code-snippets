-- =====================================================
-- AI 服务体系 - 数据库初始化脚本
-- =====================================================
--
-- 包含以下数据库和表：
-- - governance: 用户画像、向量记忆、知识库服务共享
-- - doctor_assistant: 医生助手 Agent
--
-- 执行方式：
--   mysql -h127.0.0.1 -P3306 -uroot -proot < ai/init-databases.sql
-- =====================================================

-- =====================================================
-- 1. governance - 用户画像、向量记忆、知识库共享
-- =====================================================
CREATE DATABASE IF NOT EXISTS \`governance\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE \`governance\`;




CREATE TABLE `t_customer` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` varchar(64) NOT NULL COMMENT '客户ID',
  `name` varchar(128) NOT NULL COMMENT '姓名',
  `age` int DEFAULT NULL COMMENT '年龄',
  `gender` varchar(16) DEFAULT NULL COMMENT '性别: MALE/FEMALE/OTHER',
  `phone` varchar(32) DEFAULT NULL COMMENT '手机号',
  `email` varchar(128) DEFAULT NULL COMMENT '邮箱',
  `is_deleted` tinyint DEFAULT '0' COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_id` (`customer_id`,`is_deleted`),
  KEY `idx_phone` (`phone`),
  KEY `idx_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户基本信息表';


CREATE TABLE `t_health_assessment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` varchar(64) NOT NULL COMMENT '客户ID',
  `bmi` decimal(5,2) DEFAULT NULL COMMENT 'BMI指数',
  `blood_pressure` varchar(32) DEFAULT NULL COMMENT '血压 (格式: 120/80)',
  `blood_sugar` decimal(5,2) DEFAULT NULL COMMENT '血糖 (mmol/L)',
  `assessment_date` date NOT NULL COMMENT '评估日期',
  `health_goals` text COMMENT '健康目标',
  `is_deleted` tinyint DEFAULT '0' COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_date` (`customer_id`,`assessment_date`,`is_deleted`),
  KEY `idx_assessment_date` (`assessment_date`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='健康评估表';


CREATE TABLE `b_ai_user_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `memory_type` varchar(32) NOT NULL COMMENT '记忆类型: ALLERGY/CHRONIC_DISEASE/MEDICATION/DIET_RESTRICTION/OTHER',
  `memory_key` varchar(128) NOT NULL COMMENT '记忆关键词',
  `memory_content` text NOT NULL COMMENT '记忆内容',
  `embedding_id` varchar(255) DEFAULT NULL COMMENT '向量数据库中的ID',
  `confidence_score` decimal(4,3) DEFAULT NULL COMMENT '置信度分数 0.000-1.000',
  `source` varchar(64) DEFAULT NULL COMMENT '来源: CHAT/MANUAL/IMPORT',
  `verified` tinyint DEFAULT '0' COMMENT '是否已验证: 0=未验证, 1=已验证',
  `verified_by` varchar(64) DEFAULT NULL COMMENT '验证人ID',
  `verified_at` datetime DEFAULT NULL COMMENT '验证时间',
  `is_deleted` tinyint DEFAULT '0' COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_type_key` (`customer_id`,`memory_type`,`memory_key`,`is_deleted`),
  KEY `idx_embedding_id` (`embedding_id`),
  KEY `idx_verified` (`verified`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI用户全局记忆表';


CREATE TABLE `b_user_profile_behavior_preference` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `diet_preference` varchar(255) DEFAULT NULL COMMENT '饮食偏好',
  `exercise_frequency` varchar(255) DEFAULT NULL COMMENT '运动频率',
  `check_in_streak` int DEFAULT NULL COMMENT '连续打卡天数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_behavior_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行为偏好画像表';


CREATE TABLE `b_user_profile_candidate_field` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `field_path` varchar(128) NOT NULL COMMENT '字段路径',
  `normalized_value` varchar(255) NOT NULL COMMENT '归一化值',
  `raw_value` varchar(255) NOT NULL COMMENT '原始值',
  `confidence_score` decimal(5,3) NOT NULL COMMENT '置信度',
  `status` varchar(16) NOT NULL COMMENT '状态: CANDIDATE/CONFIRMED/REJECTED/CONFLICT',
  `hit_count` int NOT NULL DEFAULT '1' COMMENT '命中次数',
  `source` varchar(64) DEFAULT NULL COMMENT '来源',
  `reviewed_by` varchar(64) DEFAULT NULL COMMENT '审核人',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_profile_candidate_customer` (`customer_id`),
  KEY `idx_profile_candidate_field` (`customer_id`,`field_path`),
  KEY `idx_profile_candidate_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户画像候选字段表';


CREATE TABLE `b_user_profile_field_conflict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `candidate_field_id` bigint NOT NULL COMMENT '候选字段ID',
  `conflict_target_type` varchar(16) NOT NULL COMMENT '冲突目标类型',
  `conflict_target_value` varchar(255) NOT NULL COMMENT '冲突目标值',
  `conflict_reason` varchar(255) NOT NULL COMMENT '冲突原因',
  `resolution_status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT '解决状态: OPEN/RESOLVED',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_profile_conflict_candidate` (`candidate_field_id`),
  KEY `idx_profile_conflict_customer` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户画像字段冲突表';


CREATE TABLE `b_ai_group_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `group_id` varchar(64) NOT NULL COMMENT '群组ID',
  `memory_type` varchar(32) NOT NULL COMMENT '记忆类型: SYMPTOM/ALLERGY/MEDICATION/DIET/PREFERENCE/OTHER',
  `memory_key` varchar(128) NOT NULL COMMENT '记忆关键词',
  `memory_content` text NOT NULL COMMENT '记忆内容',
  `embedding_id` varchar(255) DEFAULT NULL COMMENT '向量数据库中的ID',
  `confidence_score` decimal(4,3) DEFAULT NULL COMMENT '置信度分数 0.000-1.000',
  `source_message` text COMMENT '来源消息',
  `extracted_at` datetime DEFAULT NULL COMMENT '提取时间',
  `is_deleted` tinyint DEFAULT '0' COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_group` (`customer_id`,`group_id`,`is_deleted`),
  KEY `idx_memory_type` (`memory_type`),
  KEY `idx_embedding_id` (`embedding_id`)
) ENGINE=InnoDB AUTO_INCREMENT=37 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI群组记忆表';


CREATE TABLE `b_ai_group_memory_candidate` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `group_id` varchar(64) NOT NULL COMMENT '群组ID',
  `memory_type` varchar(32) NOT NULL COMMENT '记忆类型',
  `memory_key` varchar(128) NOT NULL COMMENT '原始记忆键',
  `normalized_key` varchar(128) NOT NULL COMMENT '归一化键',
  `normalized_value` varchar(255) DEFAULT NULL COMMENT '归一化值',
  `polarity` varchar(16) DEFAULT NULL COMMENT '极性: POSITIVE/NEGATIVE/NEUTRAL',
  `memory_content` text NOT NULL COMMENT '记忆内容',
  `confidence_score` decimal(5,3) NOT NULL COMMENT '置信度',
  `status` varchar(16) NOT NULL COMMENT '状态: CANDIDATE/CONFIRMED/REJECTED/CONFLICT',
  `hit_count` int NOT NULL DEFAULT '1' COMMENT '命中次数',
  `source_message` text COMMENT '来源消息',
  `last_seen_at` datetime NOT NULL COMMENT '最后出现时间',
  `reviewed_by` varchar(64) DEFAULT NULL COMMENT '审核人',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `confirmed_memory_id` bigint DEFAULT NULL COMMENT '已确认正式记忆ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_candidate_user_group` (`customer_id`,`group_id`),
  KEY `idx_candidate_status` (`status`),
  KEY `idx_candidate_normalized` (`customer_id`,`group_id`,`normalized_key`,`normalized_value`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆候选表';


CREATE TABLE `b_ai_group_memory_conflict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `group_id` varchar(64) NOT NULL COMMENT '群组ID',
  `candidate_id` bigint NOT NULL COMMENT '候选记忆ID',
  `conflict_target_type` varchar(16) NOT NULL COMMENT '冲突对象类型: CANDIDATE/CONFIRMED',
  `conflict_target_id` bigint NOT NULL COMMENT '冲突对象ID',
  `conflict_reason` varchar(255) NOT NULL COMMENT '冲突原因',
  `conflict_score` decimal(5,3) NOT NULL COMMENT '冲突分数',
  `resolution_status` varchar(16) NOT NULL DEFAULT 'OPEN' COMMENT '冲突处理状态: OPEN/RESOLVED/REJECTED',
  `resolution_reason` varchar(255) DEFAULT NULL COMMENT '冲突关闭原因',
  `resolution_note` varchar(500) DEFAULT NULL COMMENT '冲突处理备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_conflict_candidate` (`candidate_id`),
  KEY `idx_conflict_target` (`conflict_target_type`,`conflict_target_id`),
  KEY `idx_conflict_user_group` (`customer_id`,`group_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆冲突记录表';


CREATE TABLE `b_ai_group_memory_review_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `candidate_id` bigint NOT NULL COMMENT '候选记忆ID',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `group_id` varchar(64) NOT NULL COMMENT '群组ID',
  `action_type` varchar(32) NOT NULL COMMENT '动作类型: CONFIRM/REJECT/ARBITRATE',
  `result_status` varchar(32) NOT NULL COMMENT '结果状态',
  `reviewer` varchar(64) NOT NULL COMMENT '审核人',
  `resolution_reason` varchar(255) DEFAULT NULL COMMENT '关闭原因',
  `remarks` varchar(500) DEFAULT NULL COMMENT '审核备注',
  `related_formal_id` bigint DEFAULT NULL COMMENT '关联正式记忆ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_memory_review_candidate` (`candidate_id`),
  KEY `idx_memory_review_user_group` (`customer_id`,`group_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='长期记忆审核审计表';


CREATE TABLE `b_ai_chat_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` varchar(64) NOT NULL COMMENT '用户ID',
  `group_id` varchar(64) NOT NULL COMMENT '群组ID',
  `conversation_id` varchar(128) NOT NULL COMMENT 'Dify会话ID',
  `user_message` text NOT NULL COMMENT '用户消息',
  `ai_response` text COMMENT 'AI回复',
  `role` varchar(32) DEFAULT NULL COMMENT '角色类型: doctor/nutritionist/customer',
  `trace_id` varchar(64) DEFAULT NULL COMMENT '追踪ID',
  `response_time` int DEFAULT NULL COMMENT '响应时间(毫秒)',
  `is_deleted` tinyint DEFAULT '0' COMMENT '逻辑删除: 0=有效, 1=已删除',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_group` (`customer_id`,`group_id`,`is_deleted`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_trace_id` (`trace_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI聊天历史表';


CREATE TABLE `t_ai_chat_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `conversation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID',
  `user_message` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户消息',
  `ai_response` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'AI回复',
  `prompt_tokens` int DEFAULT NULL COMMENT 'Prompt tokens',
  `completion_tokens` int DEFAULT NULL COMMENT 'Completion tokens',
  `total_tokens` int DEFAULT NULL COMMENT 'Total tokens',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除（0-未删除，1-已删除）',
  PRIMARY KEY (`id`),
  KEY `idx_customer_conversation` (`customer_id`,`conversation_id`),
  KEY `idx_customer_deleted_time` (`customer_id`,`is_deleted`,`create_time`),
  KEY `idx_conversation_time` (`conversation_id`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI聊天历史表';


CREATE TABLE `knowledge_base` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®ID',
  `kb_id` varchar(64) NOT NULL COMMENT 'çŸ¥è¯†åº“ID',
  `name` varchar(255) NOT NULL COMMENT 'çŸ¥è¯†åº“åç§°',
  `description` text COMMENT 'æè¿°',
  `chunk_size` int DEFAULT '500' COMMENT 'åˆ‡ç‰‡å¤§å°',
  `chunk_overlap` int DEFAULT '50' COMMENT 'é‡å å¤§å°',
  `semantic_threshold` double DEFAULT '0.7' COMMENT 'è¯­ä¹‰ç›¸ä¼¼åº¦é˜ˆå€¼',
  `milvus_collection` varchar(64) DEFAULT NULL COMMENT 'Milvus collection åç§°',
  `embedding_dimension` int DEFAULT '1536' COMMENT 'å‘é‡ç»´åº¦',
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
  `document_count` int DEFAULT '0' COMMENT 'æ–‡æ¡£æ•°é‡',
  `chunk_count` int DEFAULT '0' COMMENT 'åˆ‡ç‰‡æ•°é‡',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'æ›´æ–°æ—¶é—´',
  `created_by` varchar(64) DEFAULT NULL COMMENT 'åˆ›å»ºè€…',
  PRIMARY KEY (`id`),
  UNIQUE KEY `kb_id` (`kb_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=60 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库表';


CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®ID',
  `kb_id` varchar(64) NOT NULL COMMENT 'çŸ¥è¯†åº“ID',
  `doc_id` varchar(64) NOT NULL COMMENT 'æ–‡æ¡£ID',
  `file_name` varchar(255) NOT NULL COMMENT 'æ–‡ä»¶å',
  `file_type` varchar(20) NOT NULL COMMENT 'pdf/md/markdown/txt',
  `file_size` bigint DEFAULT NULL COMMENT 'æ–‡ä»¶å¤§å°(å­—èŠ‚)',
  `file_path` varchar(512) DEFAULT NULL COMMENT 'å­˜å‚¨è·¯å¾„',
  `status` varchar(32) DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PROCESSING/PARSE_FAILED/SUSPECTED_DUPLICATE/DEDUPLICATED/ACTIVE/ARCHIVED/FAILED',
  `chunk_count` int DEFAULT '0' COMMENT 'åˆ‡ç‰‡æ•°é‡',
  `process_start_time` datetime DEFAULT NULL COMMENT 'å¤„ç†å¼€å§‹æ—¶é—´',
  `process_end_time` datetime DEFAULT NULL COMMENT 'å¤„ç†ç»“æŸæ—¶é—´',
  `error_message` text COMMENT 'é”™è¯¯ä¿¡æ¯',
  `upload_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'ä¸Šä¼ æ—¶é—´',
  `title` varchar(255) NOT NULL DEFAULT '' COMMENT '标题',
  `tags` varchar(512) NOT NULL DEFAULT '' COMMENT '标签，逗号分隔',
  `business_domain` varchar(128) NOT NULL DEFAULT '' COMMENT '业务域',
  `source` varchar(255) NOT NULL DEFAULT '' COMMENT '来源',
  `applicable_scope` varchar(255) NOT NULL DEFAULT '' COMMENT '适用范围',
  `trust_level` varchar(64) NOT NULL DEFAULT '' COMMENT '可信级别',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '文件内容哈希',
  `is_active` tinyint(1) DEFAULT '0' COMMENT '是否有效',
  `is_archived` tinyint(1) DEFAULT '0' COMMENT '是否归档',
  `duplicate_status` varchar(32) DEFAULT 'NONE' COMMENT 'NONE/EXACT_DUPLICATE/SUSPECTED_DUPLICATE',
  `duplicate_target_doc_id` varchar(64) DEFAULT NULL COMMENT '命中的重复文档',
  `duplicate_score` double DEFAULT NULL COMMENT '重复相似度',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `processing_stage` varchar(64) DEFAULT 'UPLOADED' COMMENT 'å½“å‰å¤„ç†é˜¶æ®µ',
  `processing_progress` int DEFAULT '0' COMMENT 'å¤„ç†è¿›åº¦ç™¾åˆ†æ¯”',
  `retry_count` int DEFAULT '0' COMMENT 'é‡è¯•æ¬¡æ•°',
  `last_retry_time` datetime DEFAULT NULL COMMENT 'æœ€è¿‘é‡è¯•æ—¶é—´',
  `last_error_stage` varchar(64) DEFAULT NULL COMMENT 'æœ€è¿‘å¤±è´¥é˜¶æ®µ',
  PRIMARY KEY (`id`),
  UNIQUE KEY `doc_id` (`doc_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_status` (`status`),
  KEY `idx_upload_time` (`upload_time`),
  KEY `idx_kb_status` (`kb_id`,`status`),
  KEY `idx_hash` (`kb_id`,`content_hash`),
  KEY `idx_active` (`kb_id`,`is_active`,`is_archived`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识文档表';


CREATE TABLE `knowledge_document_chunk` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `chunk_id` varchar(128) NOT NULL COMMENT '切片ID',
  `kb_id` varchar(64) NOT NULL COMMENT '知识库ID',
  `doc_id` varchar(64) NOT NULL COMMENT '文档ID',
  `chunk_index` int NOT NULL COMMENT '切片序号',
  `title` varchar(255) NOT NULL COMMENT '标题',
  `tags` varchar(512) NOT NULL COMMENT '标签，逗号分隔',
  `business_domain` varchar(128) NOT NULL COMMENT '业务域',
  `source` varchar(255) NOT NULL COMMENT '来源',
  `applicable_scope` varchar(255) NOT NULL COMMENT '适用范围',
  `trust_level` varchar(64) NOT NULL COMMENT '可信级别',
  `is_active` tinyint(1) DEFAULT '0' COMMENT '是否有效',
  `is_archived` tinyint(1) DEFAULT '0' COMMENT '是否归档',
  `duplicate_status` varchar(32) DEFAULT 'NONE' COMMENT '去重状态',
  `content` mediumtext NOT NULL COMMENT '切片内容',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `chunk_id` (`chunk_id`),
  KEY `idx_chunk_id` (`chunk_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_kb_active` (`kb_id`,`is_active`,`is_archived`),
  KEY `idx_filter_fields` (`kb_id`,`business_domain`,`source`,`applicable_scope`,`trust_level`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档切片表';


CREATE TABLE `knowledge_document_parse_result` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `doc_id` varchar(64) NOT NULL COMMENT '文档ID',
  `parse_status` varchar(32) DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
  `summary` text COMMENT '摘要',
  `keywords` text COMMENT '关键词，JSON或逗号分隔',
  `suggested_tags` text COMMENT '模型建议标签',
  `extended_metadata` json DEFAULT NULL COMMENT '扩展元数据',
  `semantic_dedup_result` json DEFAULT NULL COMMENT '语义去重结果',
  `parse_error_message` text COMMENT '处理错误',
  `processed_at` datetime DEFAULT NULL COMMENT '处理时间',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `doc_id` (`doc_id`),
  KEY `idx_doc_id` (`doc_id`),
  KEY `idx_parse_status` (`parse_status`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文档解析和治理结果表';



-- =====================================================
-- 2. doctor_assistant - 医生助手 Agent
-- =====================================================



CREATE TABLE `agent_execution_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'æ—¥å¿—ID',
  `session_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ä¼šè¯ID',
  `pending_task_id` bigint DEFAULT NULL COMMENT 'å¾…å®¡æ‰¹ä»»åŠ¡ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‚£è€…ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'åŒ»ç”ŸID',
  `goal` text COLLATE utf8mb4_unicode_ci COMMENT 'ä»»åŠ¡ç›®æ ‡',
  `total_steps` int DEFAULT NULL COMMENT 'æ€»æ­¥æ•°',
  `tokens_used` int DEFAULT NULL COMMENT 'æ¶ˆè€—Tokenæ•°',
  `latency_ms` bigint DEFAULT NULL COMMENT 'æ€»è€—æ—¶(ms)',
  `result_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'ç»“æžœçŠ¶æ€',
  `final_answer` text COLLATE utf8mb4_unicode_ci COMMENT 'æœ€ç»ˆç­”æ¡ˆ',
  `tool_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å·¥å…·åç§°',
  `tool_input_json` json DEFAULT NULL COMMENT 'å·¥å…·è¾“å…¥å‚æ•°',
  `tool_output_json` json DEFAULT NULL COMMENT 'å·¥å…·è¾“å‡ºç»“æžœ',
  `approval_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å®¡æ‰¹çŠ¶æ€æˆ–é£Žé™©ç­‰çº§',
  `review_comment` text COLLATE utf8mb4_unicode_ci COMMENT 'å®¡æ‰¹æ„è§',
  `reviewed_at` datetime DEFAULT NULL COMMENT 'å®¡æ‰¹æ—¶é—´',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT 'é”™è¯¯ä¿¡æ¯',
  `execution_history_json` json DEFAULT NULL COMMENT 'æ‰§è¡ŒåŽ†å²',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_doctor` (`doctor_id`),
  KEY `idx_created` (`created_at`),
  KEY `idx_pending_task` (`pending_task_id`),
  KEY `idx_result_status` (`result_status`),
  KEY `idx_approval_status` (`approval_status`)
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent执行日志表';


CREATE TABLE `agent_pending_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä»»åŠ¡ID',
  `agent_session_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Agentä¼šè¯ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‚£è€…ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'åŒ»ç”ŸID',
  `tool_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'å·¥å…·åç§°',
  `tool_description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å·¥å…·æè¿°',
  `input_json` json NOT NULL COMMENT 'è¾“å…¥å‚æ•°',
  `reason` text COLLATE utf8mb4_unicode_ci COMMENT 'æ‰§è¡ŒåŽŸå› ',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'çŠ¶æ€ï¼šPENDING/APPROVED/REJECTED',
  `review_comment` text COLLATE utf8mb4_unicode_ci COMMENT 'å®¡æ ¸æ„è§',
  `reviewed_at` datetime DEFAULT NULL COMMENT 'å®¡æ ¸æ—¶é—´',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `expire_at` datetime DEFAULT NULL COMMENT 'è¿‡æœŸæ—¶é—´ï¼ˆ24å°æ—¶ï¼‰',
  PRIMARY KEY (`id`),
  KEY `idx_session` (`agent_session_id`),
  KEY `idx_doctor_status` (`doctor_id`,`status`),
  KEY `idx_expire` (`expire_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent待确认任务表';


CREATE TABLE `agent_session` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ä¼šè¯ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‚£è€…ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'åŒ»ç”ŸID',
  `goal` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'ä»»åŠ¡ç›®æ ‡',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'çŠ¶æ€ï¼šRUNNING/PAUSED/COMPLETED/FAILED',
  `history_json` json DEFAULT NULL COMMENT 'æ‰§è¡ŒåŽ†å²ï¼ˆStepåˆ—è¡¨ï¼‰',
  `messages_json` json DEFAULT NULL COMMENT 'å¯¹è¯æ¶ˆæ¯ï¼ˆMessageåˆ—è¡¨ï¼‰',
  `current_step` int DEFAULT '0' COMMENT 'å½“å‰æ­¥æ•°',
  `final_answer` text COLLATE utf8mb4_unicode_ci COMMENT 'æœ€ç»ˆç­”æ¡ˆ',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT 'é”™è¯¯ä¿¡æ¯',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'æ›´æ–°æ—¶é—´',
  `completed_at` datetime DEFAULT NULL COMMENT 'å®Œæˆæ—¶é—´',
  `context_json` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_doctor` (`doctor_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent会话表';


CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'æ¶ˆæ¯ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‚£è€…ID',
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'è§’è‰²ï¼šUSER/ASSISTANT',
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ¶ˆæ¯å†…å®¹',
  `sentiment` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æƒ…æ„Ÿå€¾å‘ï¼šPOSITIVE/NEUTRAL/NEGATIVE',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  PRIMARY KEY (`id`),
  KEY `idx_customer_created` (`customer_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天记录表';


CREATE TABLE `follow_up_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®ID',
  `task_id` varchar(64) NOT NULL COMMENT 'ä»»åŠ¡ç¼–å·',
  `customer_id` varchar(64) NOT NULL COMMENT 'æ‚£è€…ID',
  `task_description` text NOT NULL COMMENT 'ä»»åŠ¡æè¿°',
  `due_date` date NOT NULL COMMENT 'æˆªæ­¢æ—¥æœŸ',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT 'çŠ¶æ€: PENDING-å¾…å¤„ç†, COMPLETED-å·²å®Œæˆ, CANCELLED-å·²å–æ¶ˆ',
  `created_by` varchar(50) NOT NULL COMMENT 'åˆ›å»ºè€…: AGENT-AIåŠ©æ‰‹, DOCTOR-åŒ»ç”Ÿ',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `completed_at` timestamp NULL DEFAULT NULL COMMENT 'å®Œæˆæ—¶é—´',
  `notes` text COMMENT 'å¤‡æ³¨',
  PRIMARY KEY (`id`),
  UNIQUE KEY `task_id` (`task_id`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_status` (`status`),
  KEY `idx_due_date` (`due_date`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='随访任务表';


CREATE TABLE `health_knowledge` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ä¸»é”®ID',
  `topic` varchar(100) NOT NULL COMMENT 'ä¸»é¢˜/å…³é”®è¯',
  `category` varchar(50) NOT NULL COMMENT 'åˆ†ç±»: NUTRITION-è¥å…», EXERCISE-è¿åŠ¨, DISEASE-ç–¾ç—…, MEDICATION-ç”¨è¯, LIFESTYLE-ç”Ÿæ´»æ–¹å¼',
  `title` varchar(200) NOT NULL COMMENT 'æ ‡é¢˜',
  `content` text NOT NULL COMMENT 'å†…å®¹',
  `tags` json DEFAULT NULL COMMENT 'æ ‡ç­¾ï¼ˆJSONæ•°ç»„ï¼‰',
  `source` varchar(200) DEFAULT NULL COMMENT 'æ¥æº',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`id`),
  KEY `idx_topic` (`topic`),
  KEY `idx_category` (`category`),
  FULLTEXT KEY `ft_title_content` (`title`,`content`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='健康知识库表';


CREATE TABLE `patient_profile` (
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‚£è€…ID',
  `name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'å§“å',
  `age` int DEFAULT NULL COMMENT 'å¹´é¾„',
  `gender` varchar(8) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'æ€§åˆ«',
  `height` decimal(5,2) DEFAULT NULL COMMENT 'èº«é«˜(cm)',
  `current_weight` decimal(5,2) DEFAULT NULL COMMENT 'å½“å‰ä½“é‡(kg)',
  `target_weight` decimal(5,2) DEFAULT NULL COMMENT 'ç›®æ ‡ä½“é‡(kg)',
  `bmi` decimal(5,2) DEFAULT NULL COMMENT 'BMIæŒ‡æ•°',
  `chronic_diseases` json DEFAULT NULL COMMENT 'æ…¢æ€§ç—…åˆ—è¡¨',
  `allergies` json DEFAULT NULL COMMENT 'è¿‡æ•å²',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'æ›´æ–°æ—¶é—´',
  PRIMARY KEY (`customer_id`),
  KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='患者档案表';


CREATE TABLE `prescription_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'å¤„æ–¹ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‚£è€…ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'åŒ»ç”ŸID',
  `prescription_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'å¤„æ–¹ç±»åž‹',
  `items_json` json NOT NULL COMMENT 'å¤„æ–¹é¡¹ç›®åˆ—è¡¨',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'çŠ¶æ€ï¼šDRAFT/ACTIVE/COMPLETED',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  PRIMARY KEY (`id`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_doctor` (`doctor_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='处方记录表';


CREATE TABLE `weight_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'è®°å½•ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'æ‚£è€…ID',
  `weight` decimal(5,2) NOT NULL COMMENT 'ä½“é‡(kg)',
  `bmi` decimal(5,2) DEFAULT NULL COMMENT 'BMIæŒ‡æ•°',
  `record_date` date NOT NULL COMMENT 'è®°å½•æ—¥æœŸ',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT 'åˆ›å»ºæ—¶é—´',
  PRIMARY KEY (`id`),
  KEY `idx_customer_date` (`customer_id`,`record_date`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='体重记录表';


