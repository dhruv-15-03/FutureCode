CREATE TABLE IF NOT EXISTS fc_runs (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  problem_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind ENUM('snippet', 'counter-regression') NOT NULL,
  language ENUM('java', 'python', 'javascript') NOT NULL,
  state ENUM('submitting', 'queued', 'running', 'completed', 'failed', 'unconfirmed') NOT NULL,
  source_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  result JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX runs_user_created (user_id, created_at, id),
  CONSTRAINT fc_runs_user FOREIGN KEY (user_id) REFERENCES fc_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fc_payment_links (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
  amount_paise INT UNSIGNED NOT NULL,
  currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INR',
  state ENUM('creating', 'created', 'paid', 'cancelled', 'expired', 'unconfirmed') NOT NULL,
  checkout_url VARCHAR(512) NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY payment_request (user_id, request_id),
  INDEX payments_user_created (user_id, created_at, id),
  CONSTRAINT fc_payments_user FOREIGN KEY (user_id) REFERENCES fc_users(id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fc_payment_events (
  event_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payment_link_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_type VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fc_payment_event_link FOREIGN KEY (payment_link_id) REFERENCES fc_payment_links(id) ON DELETE RESTRICT
) ENGINE=InnoDB;
