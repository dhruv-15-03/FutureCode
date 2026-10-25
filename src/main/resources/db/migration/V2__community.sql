ALTER TABLE fc_users ADD COLUMN leaderboard_opt_in BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS fc_bookmarks (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  problem_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(120) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, problem_key),
  CONSTRAINT fc_bookmarks_user FOREIGN KEY (user_id) REFERENCES fc_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fc_discussions (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  problem_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  body VARCHAR(2000) NOT NULL,
  hidden BOOLEAN NOT NULL DEFAULT FALSE,
  revision INT UNSIGNED NOT NULL DEFAULT 1,
  moderation_note VARCHAR(300) NOT NULL DEFAULT '',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX discussions_problem (problem_key, created_at, id),
  INDEX discussions_user (user_id),
  CONSTRAINT fc_discussions_user FOREIGN KEY (user_id) REFERENCES fc_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fc_problem_votes (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  problem_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  value SMALLINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, problem_key),
  CONSTRAINT fc_votes_user FOREIGN KEY (user_id) REFERENCES fc_users(id) ON DELETE CASCADE,
  CONSTRAINT fc_votes_value CHECK (value IN (-1, 1))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fc_moderation_audit (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  details JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX audit_created (created_at, id),
  CONSTRAINT fc_audit_actor FOREIGN KEY (actor_id) REFERENCES fc_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
