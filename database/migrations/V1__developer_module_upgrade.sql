-- ============================================================
-- Migration: Developer / Super Admin module upgrade
-- Safe to run repeatedly (idempotent) against an EXISTING
-- college_attendance database. Never drops or rewrites data.
--
-- Windows CMD:
--   mysql -u root -p < database\migrations\V1__developer_module_upgrade.sql
-- ============================================================

USE college_attendance;

-- ============================================================
-- 1) AUDIT LOG TABLE (administrative action trail)
-- ============================================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_email VARCHAR(100),
    actor_role VARCHAR(30),
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(40),
    target_id BIGINT,
    target_email VARCHAR(100),
    details TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_created_at (created_at),
    INDEX idx_audit_action (action),
    INDEX idx_audit_actor (actor_email)
) ENGINE=InnoDB;

-- ============================================================
-- 2) ENSURE users TABLE HAS ALL COLUMNS USED BY THE APPLICATION
--    (Hibernate ddl-auto=update adds these automatically on the
--    local profile; this script makes strict/validate setups safe.)
-- ============================================================

SET @dbname = 'college_attendance';

-- first_login
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'first_login') = 0,
    'ALTER TABLE users ADD COLUMN first_login BOOLEAN DEFAULT TRUE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- password_setup_token (stores the SHA-256 HASH of the activation/reset token)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'password_setup_token') = 0,
    'ALTER TABLE users ADD COLUMN password_setup_token VARCHAR(255) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- password_setup_token_expiry
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'password_setup_token_expiry') = 0,
    'ALTER TABLE users ADD COLUMN password_setup_token_expiry TIMESTAMP NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- leave_approver_id
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'leave_approver_id') = 0,
    'ALTER TABLE users ADD COLUMN leave_approver_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- secondary_approver_id
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'secondary_approver_id') = 0,
    'ALTER TABLE users ADD COLUMN secondary_approver_id BIGINT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- address
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'address') = 0,
    'ALTER TABLE users ADD COLUMN address TEXT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- bio
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'bio') = 0,
    'ALTER TABLE users ADD COLUMN bio TEXT NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- profile_picture_url
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_picture_url') = 0,
    'ALTER TABLE users ADD COLUMN profile_picture_url VARCHAR(500) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- is_active
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'is_active') = 0,
    'ALTER TABLE users ADD COLUMN is_active BOOLEAN DEFAULT TRUE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- last_login
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'last_login') = 0,
    'ALTER TABLE users ADD COLUMN last_login TIMESTAMP NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 3) INDEXES FOR THE DEVELOPER MODULE QUERIES
-- ============================================================
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_setup_token') = 0,
    'ALTER TABLE users ADD INDEX idx_users_setup_token (password_setup_token)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_created_at') = 0,
    'ALTER TABLE users ADD INDEX idx_users_created_at (created_at)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_is_active') = 0,
    'ALTER TABLE users ADD INDEX idx_users_is_active (is_active)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Existing data is left untouched.
SELECT 'V1__developer_module_upgrade applied successfully' AS migration_status;
