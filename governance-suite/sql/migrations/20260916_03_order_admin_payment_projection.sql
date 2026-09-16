USE order_db;

ALTER TABLE t_order_admin_read_model
  CHANGE COLUMN event_id order_event_id VARCHAR(64) NULL COMMENT '最后应用的订单事件ID',
  MODIFY COLUMN customer_id VARCHAR(64) NULL,
  MODIFY COLUMN product_id VARCHAR(64) NULL,
  MODIFY COLUMN quantity INT NULL,
  MODIFY COLUMN amount_cents BIGINT NULL,
  MODIFY COLUMN status VARCHAR(16) NULL,
  MODIFY COLUMN order_version BIGINT NOT NULL DEFAULT 0,
  MODIFY COLUMN occurred_at TIMESTAMP NULL,
  ADD COLUMN payment_status VARCHAR(16) NULL COMMENT '支付状态，NULL表示未知' AFTER occurred_at,
  ADD COLUMN pay_no VARCHAR(64) NULL COMMENT '支付单号' AFTER payment_status,
  ADD COLUMN payment_updated_at TIMESTAMP NULL COMMENT '支付更新时间' AFTER pay_no,
  ADD COLUMN payment_version BIGINT NOT NULL DEFAULT 0 COMMENT '支付事件版本，0表示未知' AFTER payment_updated_at,
  ADD COLUMN payment_event_id VARCHAR(64) NULL COMMENT '最后应用的支付事件ID' AFTER payment_version,
  ADD COLUMN payment_occurred_at TIMESTAMP NULL COMMENT '支付事件发生时间' AFTER payment_event_id,
  ADD KEY idx_payment_status_updated_at (payment_status, payment_updated_at),
  ADD KEY idx_pay_no (pay_no);

USE payment_db;

ALTER TABLE t_payment_0 ADD COLUMN version BIGINT NOT NULL DEFAULT 1 COMMENT '支付事实版本' AFTER status;
ALTER TABLE t_payment_1 ADD COLUMN version BIGINT NOT NULL DEFAULT 1 COMMENT '支付事实版本' AFTER status;

CREATE TABLE IF NOT EXISTS t_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'NEW',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP NULL DEFAULT NULL,
  error_reason VARCHAR(1000) DEFAULT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at TIMESTAMP NULL DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_status_next_retry_at (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
