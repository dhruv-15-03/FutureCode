CREATE TABLE IF NOT EXISTS fc_app_lock (
  id TINYINT UNSIGNED NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

INSERT IGNORE INTO fc_app_lock (id) VALUES (1);

CREATE TABLE IF NOT EXISTS fc_users (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  google_sub VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  email VARCHAR(254) NOT NULL,
  google_name VARCHAR(255) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  `role` ENUM('student', 'admin') NOT NULL DEFAULT 'student',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fc_sessions (
  token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  csrf_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX sessions_user_created (user_id, created_at),
  INDEX sessions_expiry (expires_at),
  CONSTRAINT fc_sessions_user FOREIGN KEY (user_id) REFERENCES fc_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS fc_problems (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  author_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(120) NOT NULL,
  language VARCHAR(32) NOT NULL,
  difficulty ENUM('introductory', 'intermediate', 'advanced') NOT NULL,
  kind ENUM('reading', 'debugging', 'test-design', 'ai-review', 'case-study') NOT NULL,
  status ENUM('draft', 'pending', 'published', 'rejected') NOT NULL DEFAULT 'draft',
  body JSON NOT NULL,
  revision INT UNSIGNED NOT NULL DEFAULT 1,
  review_note VARCHAR(1000) NOT NULL DEFAULT '',
  reviewed_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  published_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  INDEX problems_public (status, created_at, id),
  INDEX problems_author (author_id, created_at, id),
  CONSTRAINT fc_problems_author FOREIGN KEY (author_id) REFERENCES fc_users(id) ON DELETE CASCADE,
  CONSTRAINT fc_problems_reviewer FOREIGN KEY (reviewed_by) REFERENCES fc_users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS fc_attempts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  problem_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(120) NOT NULL,
  problem_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  category ENUM('curated', 'community', 'ungraded') NOT NULL,
  score SMALLINT UNSIGNED NULL,
  max_score SMALLINT UNSIGNED NULL,
  `record` JSON NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX attempts_user_created (user_id, created_at, id),
  INDEX attempts_problem_user (problem_key, user_id),
  CONSTRAINT fc_attempts_user FOREIGN KEY (user_id) REFERENCES fc_users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
