-- ============================================================
-- Migration: Enforce Exactly One System Developer Account
-- Updates the developer account display name to "System Developer"
-- and email to "admin@college.edu", preserving valid credentials.
-- Enforces database-level constraint ensuring at most 1 DEVELOPER account.
-- Safe and idempotent to run repeatedly.
-- ============================================================

USE college_attendance;

-- 1. Identify and update existing System Developer account
UPDATE users
SET first_name = 'System',
    last_name = 'Developer',
    email = 'admin@college.edu'
WHERE role = 'DEVELOPER'
ORDER BY id ASC
LIMIT 1;

-- 2. Consolidate any duplicate developer accounts safely (if any exist)
-- Demote secondary duplicate developer accounts to FACULTY or deactivate them to preserve records
UPDATE users
SET role = 'FACULTY',
    is_active = FALSE
WHERE role = 'DEVELOPER'
  AND email <> 'Dev@college.edu';

-- 3. Add generated column and unique constraint for single developer account
SET @dbname = 'college_attendance';

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND COLUMN_NAME = 'is_developer_unique') = 0,
    'ALTER TABLE users ADD COLUMN is_developer_unique TINYINT GENERATED ALWAYS AS (IF(role = ''DEVELOPER'', 1, NULL)) VIRTUAL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @dbname AND TABLE_NAME = 'users' AND INDEX_NAME = 'uk_single_developer') = 0,
    'ALTER TABLE users ADD UNIQUE KEY uk_single_developer (is_developer_unique)',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'V5__enforce_single_developer_account applied successfully' AS migration_status;
