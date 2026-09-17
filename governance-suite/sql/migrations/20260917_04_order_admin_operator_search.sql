USE order_db;

ALTER TABLE t_order_admin_read_model
    ADD COLUMN customer_search_token VARCHAR(64) NULL COMMENT '运营客户检索令牌' AFTER customer_id,
    ADD COLUMN created_at TIMESTAMP NULL COMMENT '订单创建时间' AFTER order_event_id,
    ADD KEY idx_created_at_order_no (created_at, order_no);

-- 既有读模型行没有可信的订单创建时间；在订单事实重新投影前，created_at 保持 NULL，
-- 并由运营检索查询排除，避免以事件更新时间代替创建时间破坏游标稳定性。
