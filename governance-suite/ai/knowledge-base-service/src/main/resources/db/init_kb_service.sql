-- Knowledge Base Service Database Initialization
-- 知识库服务数据库初始化脚本

USE governance;

-- 1. 知识库表
CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    kb_id VARCHAR(64) UNIQUE NOT NULL COMMENT '知识库ID',
    name VARCHAR(255) NOT NULL COMMENT '知识库名称',
    description TEXT COMMENT '描述',

    -- Chunking configuration
    chunk_size INT DEFAULT 500 COMMENT '切片大小',
    chunk_overlap INT DEFAULT 50 COMMENT '重叠大小',
    semantic_threshold DOUBLE DEFAULT 0.7 COMMENT '语义相似度阈值',

    -- Vector configuration
    milvus_collection VARCHAR(64) COMMENT 'Milvus collection 名称',
    embedding_dimension INT DEFAULT 1536 COMMENT '向量维度',

    -- Status
    status VARCHAR(20) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/DELETED',
    document_count INT DEFAULT 0 COMMENT '文档数量',
    chunk_count INT DEFAULT 0 COMMENT '切片数量',

    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    created_by VARCHAR(64) COMMENT '创建者',

    INDEX idx_kb_id (kb_id),
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库表';

-- 2. 知识文档表
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    kb_id VARCHAR(64) NOT NULL COMMENT '知识库ID',
    doc_id VARCHAR(64) UNIQUE NOT NULL COMMENT '文档ID',

    -- Required metadata
    title VARCHAR(255) NOT NULL COMMENT '标题',
    tags VARCHAR(512) NOT NULL COMMENT '标签，逗号分隔',
    business_domain VARCHAR(128) NOT NULL COMMENT '业务域',
    source VARCHAR(255) NOT NULL COMMENT '来源',
    applicable_scope VARCHAR(255) NOT NULL COMMENT '适用范围',
    trust_level VARCHAR(64) NOT NULL COMMENT '可信级别',

    -- File information
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type VARCHAR(20) NOT NULL COMMENT 'pdf/md/markdown/txt',
    file_size BIGINT COMMENT '文件大小(字节)',
    file_path VARCHAR(512) COMMENT '存储路径',
    content_hash VARCHAR(64) COMMENT '文件内容哈希',

    -- Governance status
    status VARCHAR(32) DEFAULT 'UPLOADED' COMMENT 'UPLOADED/PROCESSING/PARSE_FAILED/SUSPECTED_DUPLICATE/DEDUPLICATED/ACTIVE/ARCHIVED/FAILED',
    is_active TINYINT(1) DEFAULT 0 COMMENT '是否有效',
    is_archived TINYINT(1) DEFAULT 0 COMMENT '是否归档',
    duplicate_status VARCHAR(32) DEFAULT 'NONE' COMMENT 'NONE/EXACT_DUPLICATE/SUSPECTED_DUPLICATE',
    duplicate_target_doc_id VARCHAR(64) COMMENT '命中的重复文档',
    duplicate_score DOUBLE COMMENT '重复相似度',

    -- Processing status
    chunk_count INT DEFAULT 0 COMMENT '切片数量',
    process_start_time DATETIME COMMENT '处理开始时间',
    process_end_time DATETIME COMMENT '处理结束时间',
    processing_stage VARCHAR(64) DEFAULT 'UPLOADED' COMMENT '当前处理阶段',
    processing_progress INT DEFAULT 0 COMMENT '处理进度百分比',
    retry_count INT DEFAULT 0 COMMENT '重试次数',
    last_retry_time DATETIME COMMENT '最近重试时间',
    last_error_stage VARCHAR(64) COMMENT '最近失败阶段',
    error_message TEXT COMMENT '错误信息',

    created_by VARCHAR(64) COMMENT '创建者',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    upload_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',

    INDEX idx_kb_id (kb_id),
    INDEX idx_doc_id (doc_id),
    INDEX idx_status (status),
    INDEX idx_hash (kb_id, content_hash),
    INDEX idx_active (kb_id, is_active, is_archived),
    INDEX idx_upload_time (upload_time),
    INDEX idx_kb_status (kb_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识文档表';

-- 3. 文档解析和治理结果表
CREATE TABLE IF NOT EXISTS knowledge_document_parse_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    doc_id VARCHAR(64) UNIQUE NOT NULL COMMENT '文档ID',
    parse_status VARCHAR(32) DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED',
    summary TEXT COMMENT '摘要',
    keywords TEXT COMMENT '关键词，JSON或逗号分隔',
    suggested_tags TEXT COMMENT '模型建议标签',
    extended_metadata JSON COMMENT '扩展元数据',
    semantic_dedup_result JSON COMMENT '语义去重结果',
    parse_error_message TEXT COMMENT '处理错误',
    processed_at DATETIME COMMENT '处理时间',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_doc_id (doc_id),
    INDEX idx_parse_status (parse_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档解析和治理结果表';

-- 4. 文档切片表
CREATE TABLE IF NOT EXISTS knowledge_document_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    chunk_id VARCHAR(128) UNIQUE NOT NULL COMMENT '切片ID',
    kb_id VARCHAR(64) NOT NULL COMMENT '知识库ID',
    doc_id VARCHAR(64) NOT NULL COMMENT '文档ID',
    chunk_index INT NOT NULL COMMENT '切片序号',
    title VARCHAR(255) NOT NULL COMMENT '标题',
    tags VARCHAR(512) NOT NULL COMMENT '标签，逗号分隔',
    business_domain VARCHAR(128) NOT NULL COMMENT '业务域',
    source VARCHAR(255) NOT NULL COMMENT '来源',
    applicable_scope VARCHAR(255) NOT NULL COMMENT '适用范围',
    trust_level VARCHAR(64) NOT NULL COMMENT '可信级别',
    is_active TINYINT(1) DEFAULT 0 COMMENT '是否有效',
    is_archived TINYINT(1) DEFAULT 0 COMMENT '是否归档',
    duplicate_status VARCHAR(32) DEFAULT 'NONE' COMMENT '去重状态',
    content MEDIUMTEXT NOT NULL COMMENT '切片内容',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_chunk_id (chunk_id),
    INDEX idx_doc_id (doc_id),
    INDEX idx_kb_active (kb_id, is_active, is_archived),
    INDEX idx_filter_fields (kb_id, business_domain, source, applicable_scope, trust_level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档切片表';
