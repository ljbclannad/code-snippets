-- 本地 AI 服务使用独立命名空间。保留已有空间的名称、说明和创建时间。
SET NAMES utf8mb4;
INSERT INTO nacos.tenant_info
    (kp, tenant_id, tenant_name, tenant_desc, create_source, gmt_create, gmt_modified)
VALUES ('1', 'ai-local', 'ai-local', '本地开发启动的 AI 服务', 'nacos',
        UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE tenant_id = 'ai-local';
