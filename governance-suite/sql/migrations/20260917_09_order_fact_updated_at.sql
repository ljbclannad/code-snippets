USE order_db;

-- 旧环境订单分片可能早于后台读模型回填能力，缺少事实更新时间。
-- 新初始化脚本已包含此列；仅对已确认缺列的旧库执行一次。
-- 历史行无法追溯真实更新时间，迁移时取当前时间；后续更新由数据库维护。
ALTER TABLE t_order_0
  ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

ALTER TABLE t_order_1
  ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;
