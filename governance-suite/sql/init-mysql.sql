-- Governance Suite demo schema

CREATE DATABASE IF NOT EXISTS order_db DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS payment_db DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS inventory_db DEFAULT CHARACTER SET utf8mb4;
CREATE DATABASE IF NOT EXISTS seata DEFAULT CHARACTER SET utf8mb4;

-- ----------------------------
-- order_db
-- ----------------------------
USE order_db;

CREATE TABLE IF NOT EXISTS t_order_0 (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(64) NOT NULL,
  customer_id VARCHAR(64) NOT NULL,
  product_id VARCHAR(64) NOT NULL,
  quantity INT NOT NULL,
  amount_cents INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_customer (customer_id),
  KEY idx_created (created_at),
  UNIQUE KEY uk_order_no (order_no)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_order_1 LIKE t_order_0;

CREATE TABLE IF NOT EXISTS t_outbox (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  payload LONGTEXT NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME NOT NULL,
  sent_at DATETIME NULL,
  error_reason VARCHAR(512) NULL,
  UNIQUE KEY uk_event_id (event_id),
  KEY idx_status_id (status, id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_seckill_order_guard (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  sku_id VARCHAR(64) NOT NULL,
  req_id VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_user_sku (user_id, sku_id),
  UNIQUE KEY uk_req_id (req_id)
) ENGINE=InnoDB;

-- Seata AT undo log
CREATE TABLE IF NOT EXISTS undo_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME NOT NULL,
  log_modified DATETIME NOT NULL,
  ext VARCHAR(100) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB;

-- ----------------------------
-- payment_db
-- ----------------------------
USE payment_db;

CREATE TABLE IF NOT EXISTS t_payment_0 (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  pay_no VARCHAR(64) NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  amount_cents INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_pay_no (pay_no),
  KEY idx_order_no (order_no)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS t_payment_1 LIKE t_payment_0;

CREATE TABLE IF NOT EXISTS undo_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME NOT NULL,
  log_modified DATETIME NOT NULL,
  ext VARCHAR(100) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB;

-- ----------------------------
-- inventory_db
-- ----------------------------
USE inventory_db;

CREATE TABLE IF NOT EXISTS t_stock (
  product_id VARCHAR(64) PRIMARY KEY,
  available INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

INSERT INTO t_stock(product_id, available, version) VALUES
('p1', 100, 0)
ON DUPLICATE KEY UPDATE available=available;

CREATE TABLE IF NOT EXISTS undo_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  context VARCHAR(128) NOT NULL,
  rollback_info LONGBLOB NOT NULL,
  log_status INT NOT NULL,
  log_created DATETIME NOT NULL,
  log_modified DATETIME NOT NULL,
  ext VARCHAR(100) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE=InnoDB;

-- ----------------------------
-- seata (optional, for seata-server store=db)
-- ----------------------------
USE seata;

CREATE TABLE IF NOT EXISTS global_table (
  xid VARCHAR(128) NOT NULL,
  transaction_id BIGINT,
  status TINYINT NOT NULL,
  application_id VARCHAR(32),
  transaction_service_group VARCHAR(32),
  transaction_name VARCHAR(128),
  timeout INT,
  begin_time BIGINT,
  application_data VARCHAR(2000),
  gmt_create DATETIME,
  gmt_modified DATETIME,
  PRIMARY KEY (xid),
  KEY idx_gmt_modified (gmt_modified),
  KEY idx_status (status, gmt_modified)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS branch_table (
  branch_id BIGINT NOT NULL,
  xid VARCHAR(128) NOT NULL,
  transaction_id BIGINT,
  resource_group_id VARCHAR(32),
  resource_id VARCHAR(256),
  branch_type VARCHAR(8),
  status TINYINT,
  client_id VARCHAR(64),
  application_data VARCHAR(2000),
  gmt_create DATETIME,
  gmt_modified DATETIME,
  PRIMARY KEY (branch_id),
  KEY idx_xid (xid)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS lock_table (
  row_key VARCHAR(128) NOT NULL,
  xid VARCHAR(128),
  transaction_id BIGINT,
  branch_id BIGINT,
  resource_id VARCHAR(256),
  table_name VARCHAR(32),
  pk VARCHAR(36),
  status TINYINT NOT NULL DEFAULT 0,
  gmt_create DATETIME,
  gmt_modified DATETIME,
  PRIMARY KEY (row_key),
  KEY idx_xid (xid),
  KEY idx_branch_id (branch_id)
) ENGINE=InnoDB;
