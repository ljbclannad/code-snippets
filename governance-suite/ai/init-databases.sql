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
CREATE DATABASE IF NOT EXISTS `governance` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `governance`;




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


CREATE TABLE IF NOT EXISTS ai_chat_conversation_owner (
    conversation_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    customer_id VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '历史归属冲突时为空，拒绝访问'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `t_ai_chat_history` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `conversation_id` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID',
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
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `kb_id` varchar(64) NOT NULL COMMENT '知识库ID',
  `name` varchar(255) NOT NULL COMMENT '知识库名称',
  `description` text COMMENT '描述',
  `chunk_size` int DEFAULT '500' COMMENT '切片大小',
  `chunk_overlap` int DEFAULT '50' COMMENT '重叠大小',
  `semantic_threshold` double DEFAULT '0.7' COMMENT '语义相似度阈值',
  `milvus_collection` varchar(64) DEFAULT NULL COMMENT 'Milvus collection 名称',
  `embedding_dimension` int DEFAULT '1536' COMMENT '向量维度',
  `status` varchar(20) DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/INACTIVE',
  `document_count` int DEFAULT '0' COMMENT '文档数量',
  `chunk_count` int DEFAULT '0' COMMENT '切片数量',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  PRIMARY KEY (`id`),
  UNIQUE KEY `kb_id` (`kb_id`),
  KEY `idx_kb_id` (`kb_id`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=60 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库表';


CREATE TABLE `knowledge_document` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `kb_id` varchar(64) NOT NULL COMMENT '知识库ID',
  `doc_id` varchar(64) NOT NULL COMMENT '文档ID',
  `file_name` varchar(255) NOT NULL COMMENT '文件名',
  `file_type` varchar(20) NOT NULL COMMENT '文件类型',
  `file_size` bigint DEFAULT NULL COMMENT '文件大小(字节)',
  `file_path` varchar(512) DEFAULT NULL COMMENT '存储路径',
  `status` varchar(32) DEFAULT 'UPLOADED' COMMENT '状态',
  `chunk_count` int DEFAULT '0' COMMENT '切片数量',
  `process_start_time` datetime DEFAULT NULL COMMENT '处理开始时间',
  `process_end_time` datetime DEFAULT NULL COMMENT '处理结束时间',
  `error_message` text COMMENT '错误信息',
  `upload_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `title` varchar(255) NOT NULL COMMENT '文档标题',
  `tags` varchar(512) NOT NULL COMMENT '标签',
  `business_domain` varchar(128) NOT NULL COMMENT '业务领域',
  `source` varchar(255) NOT NULL COMMENT '来源',
  `applicable_scope` varchar(255) NOT NULL COMMENT '适用范围',
  `trust_level` varchar(64) NOT NULL COMMENT '可信度级别',
  `content_hash` varchar(64) DEFAULT NULL COMMENT '内容哈希',
  `is_active` tinyint(1) DEFAULT '0' COMMENT '是否激活',
  `is_archived` tinyint(1) DEFAULT '0' COMMENT '是否归档',
  `duplicate_status` varchar(32) DEFAULT 'NONE' COMMENT '重复状态',
  `duplicate_target_doc_id` varchar(64) DEFAULT NULL COMMENT '重复目标文档ID',
  `duplicate_score` double DEFAULT NULL COMMENT '重复相似度分数',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `processing_stage` varchar(64) DEFAULT 'UPLOADED' COMMENT '当前处理阶段',
  `processing_progress` int DEFAULT '0' COMMENT '处理进度百分比',
  `retry_count` int DEFAULT '0' COMMENT '重试次数',
  `last_retry_time` datetime DEFAULT NULL COMMENT '最近重试时间',
  `last_error_stage` varchar(64) DEFAULT NULL COMMENT '最近失败阶段',
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
  `chunk_index` int NOT NULL COMMENT '切片索引',
  `title` varchar(255) NOT NULL COMMENT '标题',
  `tags` varchar(512) NOT NULL COMMENT '标签',
  `business_domain` varchar(128) NOT NULL COMMENT '业务领域',
  `source` varchar(255) NOT NULL COMMENT '来源',
  `applicable_scope` varchar(255) NOT NULL COMMENT '适用范围',
  `trust_level` varchar(64) NOT NULL COMMENT '可信度级别',
  `is_active` tinyint(1) DEFAULT '0' COMMENT '是否激活',
  `is_archived` tinyint(1) DEFAULT '0' COMMENT '是否归档',
  `duplicate_status` varchar(32) DEFAULT 'NONE' COMMENT '重复状态',
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
  `parse_status` varchar(32) DEFAULT 'PENDING' COMMENT '解析状态',
  `summary` text COMMENT '摘要',
  `keywords` text COMMENT '关键词',
  `suggested_tags` text COMMENT '建议标签',
  `extended_metadata` json DEFAULT NULL COMMENT '扩展元数据',
  `semantic_dedup_result` json DEFAULT NULL COMMENT '语义去重结果',
  `parse_error_message` text COMMENT '解析错误信息',
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
CREATE DATABASE IF NOT EXISTS `doctor_assistant` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `doctor_assistant`;

CREATE TABLE `agent_execution_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `session_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID',
  `pending_task_id` bigint DEFAULT NULL COMMENT '待处理任务ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '医生ID',
  `goal` text COLLATE utf8mb4_unicode_ci COMMENT '目标',
  `total_steps` int DEFAULT NULL COMMENT '总步数',
  `tokens_used` int DEFAULT NULL COMMENT 'Token使用量',
  `latency_ms` bigint DEFAULT NULL COMMENT '延迟(毫秒)',
  `result_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '结果状态',
  `final_answer` text COLLATE utf8mb4_unicode_ci COMMENT '最终答案',
  `tool_name` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具名称',
  `tool_input_json` json DEFAULT NULL COMMENT '工具输入JSON',
  `tool_output_json` json DEFAULT NULL COMMENT '工具输出JSON',
  `approval_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '审批状态',
  `review_comment` text COLLATE utf8mb4_unicode_ci COMMENT '审核评论',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `execution_history_json` json DEFAULT NULL COMMENT '执行历史JSON',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
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
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `agent_session_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'Agent会话ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '医生ID',
  `tool_name` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具名称',
  `tool_description` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '工具描述',
  `input_json` json NOT NULL COMMENT '输入JSON',
  `reason` text COLLATE utf8mb4_unicode_ci COMMENT '原因',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态',
  `review_comment` text COLLATE utf8mb4_unicode_ci COMMENT '审核评论',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `expire_at` datetime DEFAULT NULL COMMENT '过期时间',
  PRIMARY KEY (`id`),
  KEY `idx_session` (`agent_session_id`),
  KEY `idx_doctor_status` (`doctor_id`,`status`),
  KEY `idx_expire` (`expire_at`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent待确认任务表';

-- 在 doctor_assistant 数据库执行。审批记录和站内投递共用本地事务。
CREATE TABLE IF NOT EXISTS agent_follow_up_reminder (
    customer_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    pending_task_id BIGINT UNIQUE,
    body VARCHAR(255) NOT NULL,
    delivered_at DATETIME(6),
    PRIMARY KEY (customer_id, request_id),
    INDEX idx_follow_up_inbox (customer_id, delivered_at, pending_task_id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;



CREATE TABLE `agent_session` (
  `id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话ID',
  `conversation_id` varchar(128) DEFAULT NULL COMMENT '患者侧会话编号',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '医生ID',
  `goal` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '目标',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态',
  `history_json` json DEFAULT NULL COMMENT '历史JSON',
  `messages_json` json DEFAULT NULL COMMENT '消息JSON',
  `current_step` int DEFAULT '0' COMMENT '当前步骤',
  `final_answer` text COLLATE utf8mb4_unicode_ci COMMENT '最终答案',
  `error_message` text COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  `context_json` text COLLATE utf8mb4_unicode_ci COMMENT '上下文JSON',
  PRIMARY KEY (`id`),
  KEY idx_review_conversation (customer_id, conversation_id),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_doctor` (`doctor_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent会话表';


CREATE TABLE `chat_message` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色',
  `content` text COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '内容',
  `sentiment` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '情感',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_created` (`customer_id`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天记录表';


CREATE TABLE `follow_up_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` varchar(64) NOT NULL COMMENT '任务ID',
  `customer_id` varchar(64) NOT NULL COMMENT '客户ID',
  `task_description` text NOT NULL COMMENT '任务描述',
  `due_date` date NOT NULL COMMENT '截止日期',
  `status` varchar(16) NOT NULL COMMENT '状态',
  `created_by` varchar(64) DEFAULT NULL COMMENT '创建者',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `completed_at` datetime DEFAULT NULL COMMENT '完成时间',
  `notes` text COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `task_id` (`task_id`),
  KEY `idx_customer_id` (`customer_id`),
  KEY `idx_status` (`status`),
  KEY `idx_due_date` (`due_date`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='随访任务表';


CREATE TABLE `health_knowledge` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `topic` varchar(255) DEFAULT NULL COMMENT '主题',
  `category` varchar(64) DEFAULT NULL COMMENT '分类',
  `title` varchar(255) NOT NULL COMMENT '标题',
  `content` text NOT NULL COMMENT '内容',
  `tags` varchar(512) DEFAULT NULL COMMENT '标签',
  `source` varchar(255) DEFAULT NULL COMMENT '来源',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_topic` (`topic`),
  KEY `idx_category` (`category`),
  FULLTEXT KEY `ft_title_content` (`title`,`content`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='健康知识库表';


CREATE TABLE `patient_profile` (
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '姓名',
  `age` int DEFAULT NULL COMMENT '年龄',
  `gender` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '性别',
  `height` decimal(5,2) DEFAULT NULL COMMENT '身高(cm)',
  `current_weight` decimal(5,2) DEFAULT NULL COMMENT '当前体重(kg)',
  `target_weight` decimal(5,2) DEFAULT NULL COMMENT '目标体重(kg)',
  `bmi` decimal(5,2) DEFAULT NULL COMMENT 'BMI指数',
  `chronic_diseases` text COLLATE utf8mb4_unicode_ci COMMENT '慢性病',
  `allergies` text COLLATE utf8mb4_unicode_ci COMMENT '过敏史',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`customer_id`),
  KEY `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='患者档案表';


CREATE TABLE `prescription_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `doctor_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '医生ID',
  `prescription_type` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '处方类型',
  `items_json` json DEFAULT NULL COMMENT '项目JSON',
  `status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '状态',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer` (`customer_id`),
  KEY `idx_doctor` (`doctor_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='处方记录表';


CREATE TABLE `weight_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `customer_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '客户ID',
  `weight` decimal(5,2) NOT NULL COMMENT '体重(kg)',
  `bmi` decimal(5,2) DEFAULT NULL COMMENT 'BMI指数',
  `record_date` date NOT NULL COMMENT '记录日期',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_customer_date` (`customer_id`,`record_date`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='体重记录表';


