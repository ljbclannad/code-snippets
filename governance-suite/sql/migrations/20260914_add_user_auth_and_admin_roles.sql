-- 用户认证与后台角色迁移
-- 执行前会校验旧 t_user_account 是否为可识别的认证结构；余额结构或混合结构将中止，避免猜测数据语义。
USE user_db;

-- 本迁移仅覆盖认证账户迁移。若现有 t_user_0/t_user_1 没有 user_id、status、display_name，
-- 该环境的用户资料表基线不受支持，应先执行独立的资料表迁移，避免对分片资料数据做猜测性变换。

CREATE TABLE IF NOT EXISTS `t_user_auth_account` (
  `user_id` BIGINT NOT NULL,
  `username` VARCHAR(64) NOT NULL,
  `password_hash` VARCHAR(128) NOT NULL,
  `password_salt` VARCHAR(128) NOT NULL,
  `status` VARCHAR(16) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `uk_user_auth_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `t_user_admin_role` (
  `user_id` BIGINT NOT NULL,
  `role` VARCHAR(32) NOT NULL,
  `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`, `role`),
  CONSTRAINT `ck_user_admin_role` CHECK (`role` IN ('ORDER_OPERATOR', 'FINANCE_VIEWER', 'INVENTORY_VIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS migrate_user_auth_account;
DELIMITER //
CREATE PROCEDURE migrate_user_auth_account()
BEGIN
  DECLARE username_column_count INT DEFAULT 0;
  DECLARE password_hash_column_count INT DEFAULT 0;
  DECLARE password_salt_column_count INT DEFAULT 0;
  DECLARE status_column_count INT DEFAULT 0;
  DECLARE balance_column_count INT DEFAULT 0;
  DECLARE conflicting_account_count INT DEFAULT 0;

  SELECT COUNT(*) INTO username_column_count FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_user_account' AND column_name = 'username';
  SELECT COUNT(*) INTO password_hash_column_count FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_user_account' AND column_name = 'password_hash';
  SELECT COUNT(*) INTO password_salt_column_count FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_user_account' AND column_name = 'password_salt';
  SELECT COUNT(*) INTO status_column_count FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_user_account' AND column_name = 'status';
  SELECT COUNT(*) INTO balance_column_count FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_user_account' AND column_name = 'balance_cents';

  IF username_column_count = 1 AND password_hash_column_count = 1
      AND password_salt_column_count = 1 AND status_column_count = 1
      AND balance_column_count = 0 THEN
    SELECT COUNT(*) INTO conflicting_account_count
    FROM t_user_account legacy
    JOIN t_user_auth_account target ON target.user_id = legacy.user_id OR target.username = legacy.username
    WHERE target.user_id <> legacy.user_id OR target.username <> legacy.username;
    IF conflicting_account_count > 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'authentication account migration has conflicting user IDs or usernames';
    END IF;

    INSERT IGNORE INTO t_user_auth_account(user_id, username, password_hash, password_salt, status, created_at)
    SELECT user_id, username, password_hash, password_salt, status, created_at
    FROM t_user_account;
  ELSEIF username_column_count = 0 AND password_hash_column_count = 0
      AND password_salt_column_count = 0 AND status_column_count = 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_user_account is a balance table; create authentication accounts through a controlled operation';
  ELSE
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_user_account has an unrecognized mixed schema; manual migration is required';
  END IF;
END//
DELIMITER ;
CALL migrate_user_auth_account();
DROP PROCEDURE migrate_user_auth_account;
