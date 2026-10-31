-- ============================================================
-- Migration: First-time account activation / password setup
-- Adds the token metadata required by the activation workflow:
--   * password_setup_token_purpose  (ACTIVATION vs PASSWORD_RESET)
--   * password_setup_token_created_at
--   * password_setup_token_used_at
--   * activated_at
--
-- Idempotent: safe to run repeatedly against an EXISTING
-- college_attendance database. Adds columns and backfills values
-- only where they are NULL. Never drops or rewrites data.
--
-- Windows CMD:
--   mysql -u root -p < database\migrations\V2__first_time_activation_workflow.sql
-- ============================================================

USE college_attendance;

SET @dbname = 'college_attendance';

-- ------------------------------------------------
-- 1) password_setup_token_purpose
-- ------------------------------------------------
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users'
       AND COLUMN_NAME = 'password_setup_token_purpose') = 0,
    'ALTER TABLE users ADD COLUMN password_setup_token_purpose VARCHAR(20) NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------
-- 2) password_setup_token_created_at
-- ------------------------------------------------
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users'
       AND COLUMN_NAME = 'password_setup_token_created_at') = 0,
    'ALTER TABLE users ADD COLUMN password_setup_token_created_at TIMESTAMP NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------
-- 3) password_setup_token_used_at
-- ------------------------------------------------
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users'
       AND COLUMN_NAME = 'password_setup_token_used_at') = 0,
    'ALTER TABLE users ADD COLUMN password_setup_token_used_at TIMESTAMP NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------
-- 4) activated_at
-- ------------------------------------------------
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users'
       AND COLUMN_NAME = 'activated_at') = 0,
    'ALTER TABLE users ADD COLUMN activated_at TIMESTAMP NULL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ------------------------------------------------
-- 5) BACKFILL existing rows (only where NULL)
--    Outstanding tokens on never-logged-in accounts are
--    first-time ACTIVATION links; anything else outstanding
--    on an already-active account is a PASSWORD_RESET link.
-- ------------------------------------------------
UPDATE users
   SET password_setup_token_purpose = 'ACTIVATION'
 WHERE password_setup_token_purpose IS NULL
   AND password_setup_token IS NOT NULL
   AND (first_login = 1 OR first_login IS NULL);

UPDATE users
   SET password_setup_token_purpose = 'PASSWORD_RESET'
 WHERE password_setup_token_purpose IS NULL
   AND password_setup_token IS NOT NULL;

-- Records when an outstanding token was issued (best effort).
UPDATE users
   SET password_setup_token_created_at = COALESCE(updated_at, created_at)
 WHERE password_setup_token_created_at IS NULL
   AND password_setup_token IS NOT NULL;

-- Accounts that already completed activation get an activation timestamp.
UPDATE users
   SET activated_at = COALESCE(updated_at, created_at)
 WHERE activated_at IS NULL
   AND is_active = 1
   AND password_setup_token IS NULL
   AND first_login = 0;

-- ------------------------------------------------
-- 6) Index used by token lookups / purpose filters
-- ------------------------------------------------
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users'
       AND INDEX_NAME = 'idx_users_token_purpose') = 0,
    'ALTER TABLE users ADD INDEX idx_users_token_purpose (password_setup_token_purpose)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'V2__first_time_activation_workflow applied successfully' AS migration_status;
