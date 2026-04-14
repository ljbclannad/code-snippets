-- 创建数据库
CREATE DATABASE IF NOT EXISTS medical_report
DEFAULT CHARACTER SET utf8mb4
DEFAULT COLLATE utf8mb4_unicode_ci;

USE medical_report;

-- 创建医疗报告历史表
CREATE TABLE IF NOT EXISTS medical_report_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    patient_name VARCHAR(100) NOT NULL COMMENT '患者姓名',
    age VARCHAR(10) COMMENT '年龄',
    gender VARCHAR(10) COMMENT '性别',
    questionnaire_data TEXT COMMENT '问卷数据JSON',
    health_advice TEXT COMMENT '健康建议',
    risk_assessment TEXT COMMENT '风险评估',
    medical_guidance TEXT COMMENT '就医指导',
    report_date VARCHAR(20) COMMENT '报告日期',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    pdf_url VARCHAR(500) COMMENT 'PDF文件URL',
    pdf_content TEXT COMMENT 'PDF内容',
    INDEX idx_patient_name (patient_name),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='医疗报告历史记录表';
