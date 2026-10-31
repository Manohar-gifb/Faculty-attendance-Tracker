-- ============================================================
-- V1__baseline_schema.sql
-- College Faculty Attendance & Management System
-- Authoritative Flyway V1 Baseline Migration
-- Derived from Hibernate 6 MySQL 8 entity schema export
-- ============================================================

-- 1. DEPARTMENTS TABLE
CREATE TABLE IF NOT EXISTS departments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    is_active BIT DEFAULT 1,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_departments_code UNIQUE (code),
    CONSTRAINT uk_departments_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. USERS TABLE (Faculty, Institutional Admins, Developer)
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    employee_id VARCHAR(20) NOT NULL,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('DEVELOPER', 'VC', 'PRINCIPAL', 'TPO', 'HOD', 'FACULTY') NOT NULL,
    department_id BIGINT,
    leave_approver_id BIGINT,
    secondary_approver_id BIGINT,
    phone VARCHAR(20),
    address TEXT,
    bio TEXT,
    profile_picture_url VARCHAR(500),
    is_active BIT DEFAULT 1,
    first_login BIT DEFAULT 0,
    password_setup_token VARCHAR(255),
    password_setup_token_expiry DATETIME(6),
    password_setup_token_purpose ENUM('ACTIVATION', 'PASSWORD_RESET'),
    password_setup_token_created_at DATETIME(6),
    password_setup_token_used_at DATETIME(6),
    activated_at DATETIME(6),
    last_login DATETIME(6),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    is_developer_unique TINYINT GENERATED ALWAYS AS (IF(role = 'DEVELOPER', 1, NULL)) VIRTUAL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_employee_id UNIQUE (employee_id),
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT uk_users_password_setup_token UNIQUE (password_setup_token),
    CONSTRAINT uk_single_developer UNIQUE (is_developer_unique),
    CONSTRAINT fk_users_department FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE SET NULL,
    CONSTRAINT fk_users_leave_approver FOREIGN KEY (leave_approver_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_users_secondary_approver FOREIGN KEY (secondary_approver_id) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_users_role (role),
    INDEX idx_users_department (department_id),
    INDEX idx_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. ATTENDANCE LOGS TABLE
CREATE TABLE IF NOT EXISTS attendance_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    punch_in_time DATETIME(6),
    punch_out_time DATETIME(6),
    working_hours DECIMAL(5, 2) DEFAULT 0.00,
    status ENUM('PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'ON_LEAVE') NOT NULL DEFAULT 'ABSENT',
    is_late BIT DEFAULT 0,
    late_minutes INT DEFAULT 0,
    latitude FLOAT(53),
    longitude FLOAT(53),
    location_accuracy FLOAT(53),
    distance_from_college FLOAT(53),
    face_confidence FLOAT(53),
    verification_status VARCHAR(50) DEFAULT 'UNVERIFIED',
    remarks TEXT,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_attendance_user_date UNIQUE (user_id, attendance_date),
    CONSTRAINT fk_attendance_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    INDEX idx_attendance_date (attendance_date),
    INDEX idx_attendance_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. LEAVE REQUESTS TABLE
CREATE TABLE IF NOT EXISTS leave_requests (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    leave_type ENUM('CASUAL', 'MEDICAL', 'OD', 'EARNED', 'SICK', 'MATERNITY', 'PATERNITY') NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason TEXT NOT NULL,
    attachment_url VARCHAR(500),
    status ENUM('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
    approved_by BIGINT,
    approval_token VARCHAR(255),
    approval_token_expiry DATETIME(6),
    reviewer_comments TEXT,
    reviewed_at DATETIME(6),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_leave_approval_token UNIQUE (approval_token),
    CONSTRAINT fk_leave_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_leave_approved_by FOREIGN KEY (approved_by) REFERENCES users (id) ON DELETE SET NULL,
    INDEX idx_leave_user (user_id),
    INDEX idx_leave_status (status),
    INDEX idx_leave_dates (start_date, end_date),
    INDEX idx_leave_token (approval_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. AUDIT LOGS TABLE
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    actor_email VARCHAR(100),
    actor_role VARCHAR(30),
    action VARCHAR(60) NOT NULL,
    target_type VARCHAR(40),
    target_id BIGINT,
    target_email VARCHAR(100),
    details TEXT,
    created_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_audit_created_at (created_at),
    INDEX idx_audit_action (action),
    INDEX idx_audit_actor (actor_email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
