-- schema for idgen-demo
CREATE TABLE IF NOT EXISTS id_alloc (
  biz_tag     VARCHAR(64) NOT NULL PRIMARY KEY,
  max_id      BIGINT      NOT NULL,
  step        INT         NOT NULL,
  update_time DATETIME    NOT NULL,
  version     BIGINT      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO id_alloc(biz_tag, max_id, step, update_time, version)
VALUES ('order', 0, 5000, NOW(), 0)
ON DUPLICATE KEY UPDATE update_time = VALUES(update_time);

CREATE TABLE IF NOT EXISTS t_order (
  id          BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  user_id     BIGINT UNSIGNED NOT NULL,
  amount      DECIMAL(18,2)   NOT NULL,
  status      VARCHAR(32)     NOT NULL,
  create_time DATETIME        NOT NULL,
  KEY idx_user_time(user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
