USE order_db;

ALTER TABLE t_order_admin_export_task
  ADD COLUMN criteria_snapshot TEXT NOT NULL COMMENT '冻结的检索条件' AFTER criteria_fingerprint,
  ADD COLUMN field_set VARCHAR(500) NOT NULL COMMENT '冻结的财务可见字段集' AFTER criteria_snapshot,
  ADD COLUMN role_snapshot VARCHAR(500) NOT NULL COMMENT '创建时可信角色快照' AFTER field_set,
  ADD COLUMN search_scope VARCHAR(500) NOT NULL COMMENT '创建时可信搜索范围' AFTER role_snapshot,
  ADD COLUMN read_model_watermark TIMESTAMP NOT NULL COMMENT '创建时读模型水位' AFTER search_scope;
