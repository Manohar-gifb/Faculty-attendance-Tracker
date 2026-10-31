-- ============================================================
-- College Faculty Attendance & Management System
-- MySQL Database Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS college_attendance;
USE college_attendance;

-- ============================================================
-- DEPARTMENTS TABLE
-- ============================================================
CREATE TABLE departments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    code VARCHAR(20) NOT NULL UNIQUE,
    description TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ============================================================
-- USERS TABLE (Faculty + Admins + Developer)
-- ============================================================
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    employee_id VARCHAR(20) NOT NULL UNIQUE,
    first_name VARCHAR(50) NOT NULL,
    last_name VARCHAR(50) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role ENUM('DEVELOPER', 'VC', 'PRINCIPAL', 'TPO', 'HOD', 'FACULTY') NOT NULL DEFAULT 'FACULTY',
    department_id BIGINT,
    phone VARCHAR(20),
    address TEXT,
    bio TEXT,
    profile_picture_url VARCHAR(500),
    is_active BOOLEAN DEFAULT TRUE,
    first_login BOOLEAN DEFAULT FALSE,
    leave_approver_id BIGINT,
    secondary_approver_id BIGINT,
    password_setup_token VARCHAR(255),
    password_setup_token_expiry TIMESTAMP NULL,
    password_setup_token_purpose ENUM('ACTIVATION', 'PASSWORD_RESET') NULL,
    password_setup_token_created_at TIMESTAMP NULL,
    password_setup_token_used_at TIMESTAMP NULL,
    activated_at TIMESTAMP NULL,
    last_login TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_developer_unique TINYINT GENERATED ALWAYS AS (IF(role = 'DEVELOPER', 1, NULL)) VIRTUAL,
    FOREIGN KEY (department_id) REFERENCES departments(id) ON DELETE SET NULL,
    FOREIGN KEY (leave_approver_id) REFERENCES users(id) ON DELETE SET NULL,
    FOREIGN KEY (secondary_approver_id) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE KEY uk_single_developer (is_developer_unique),
    UNIQUE KEY uk_password_setup_token (password_setup_token),
    INDEX idx_users_role (role),
    INDEX idx_users_department (department_id),
    INDEX idx_users_email (email)
) ENGINE=InnoDB;

-- ============================================================
-- ATTENDANCE LOGS TABLE
-- ============================================================
CREATE TABLE attendance_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    attendance_date DATE NOT NULL,
    punch_in_time TIMESTAMP NULL,
    punch_out_time TIMESTAMP NULL,
    working_hours DECIMAL(5, 2) DEFAULT 0.00,
    status ENUM('PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'ON_LEAVE') NOT NULL DEFAULT 'ABSENT',
    is_late BOOLEAN DEFAULT FALSE,
    late_minutes INT DEFAULT 0,
    latitude DECIMAL(10, 7) NULL,
    longitude DECIMAL(10, 7) NULL,
    location_accuracy DECIMAL(8, 2) NULL,
    distance_from_college DECIMAL(10, 2) NULL,
    face_confidence DECIMAL(5, 2) NULL,
    verification_status VARCHAR(50) DEFAULT 'UNVERIFIED',
    remarks TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_attendance_user_date (user_id, attendance_date),
    INDEX idx_attendance_date (attendance_date),
    INDEX idx_attendance_status (status)
) ENGINE=InnoDB;

-- ============================================================
-- LEAVE REQUESTS TABLE
-- ============================================================
CREATE TABLE leave_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    leave_type ENUM('CASUAL', 'MEDICAL', 'OD', 'EARNED', 'SICK', 'MATERNITY', 'PATERNITY') NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason TEXT NOT NULL,
    attachment_url VARCHAR(500),
    status ENUM('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED') NOT NULL DEFAULT 'PENDING',
    approved_by BIGINT,
    approval_token VARCHAR(255) UNIQUE,
    approval_token_expiry TIMESTAMP NULL,
    reviewer_comments TEXT,
    reviewed_at TIMESTAMP NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (approved_by) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_leave_user (user_id),
    INDEX idx_leave_status (status),
    INDEX idx_leave_dates (start_date, end_date),
    INDEX idx_leave_token (approval_token)
) ENGINE=InnoDB;

-- ============================================================
-- NOTIFICATIONS TABLE (for in-app notifications)
-- ============================================================
CREATE TABLE notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    type ENUM('LEAVE_REQUEST', 'LEAVE_APPROVED', 'LEAVE_REJECTED', 'ATTENDANCE', 'SYSTEM') NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    related_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_notifications_user (user_id, is_read)
) ENGINE=InnoDB;

-- ============================================================
-- AUDIT LOGS TABLE (administrative audit trail —
-- mirrors com.college.attendance.entity.AuditLog)
-- ============================================================
CREATE TABLE audit_logs (
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
-- SEED DATA: Departments
-- ============================================================
INSERT INTO departments (name, code, description) VALUES
('Computer Science & Engineering', 'CSE', 'Department of Computer Science & Engineering'),
('Information Technology', 'IT', 'Department of Information Technology'),
('Electronics & Communication', 'ECE', 'Department of Electronics & Communication Engineering'),
('Electrical Engineering', 'EE', 'Department of Electrical Engineering'),
('Mechanical Engineering', 'ME', 'Department of Mechanical Engineering'),
('Civil Engineering', 'CE', 'Department of Civil Engineering'),
('Chemical Engineering', 'CHE', 'Department of Chemical Engineering'),
('Mathematics', 'MATH', 'Department of Mathematics'),
('Physics', 'PHY', 'Department of Physics'),
('Humanities & Social Sciences', 'HSS', 'Department of Humanities & Social Sciences');

-- ============================================================
-- SEED DATA: Authorized System Developer Account
-- Role: System Developer
-- Email: admin@college.edu
-- Note: Created/managed via Spring Boot DataSeeder.java using BCrypt.
-- Exactly one developer account is enforced by uk_single_developer.
-- ============================================================

-- ============================================================
-- SEED DATA: Baseline Sample Accounts for testing
-- Note: Seeded by DataSeeder.java on application startup.
-- Passwords should be supplied via environment variables in production.
-- ============================================================
-- These will be seeded by the DataSeeder on application startup.
-- See src/main/java/com/college/attendance/config/DataSeeder.java

-- ============================================================
-- HELPFUL VIEWS
-- ============================================================

-- View: Today's attendance summary per department
CREATE VIEW vw_today_attendance AS
SELECT
    d.id AS department_id,
    d.name AS department_name,
    d.code AS department_code,
    COUNT(DISTINCT u.id) AS total_faculty,
    SUM(CASE WHEN al.status = 'PRESENT' THEN 1 ELSE 0 END) AS present_count,
    SUM(CASE WHEN al.status = 'ABSENT' THEN 1 ELSE 0 END) AS absent_count,
    SUM(CASE WHEN al.status = 'LATE' THEN 1 ELSE 0 END) AS late_count,
    SUM(CASE WHEN al.status = 'ON_LEAVE' THEN 1 ELSE 0 END) AS on_leave_count,
    CURDATE() AS attendance_date
FROM departments d
LEFT JOIN users u ON u.department_id = d.id AND u.role = 'FACULTY' AND u.is_active = TRUE
LEFT JOIN attendance_logs al ON al.user_id = u.id AND al.attendance_date = CURDATE()
GROUP BY d.id, d.name, d.code;

-- View: Faculty attendance report (monthly)
CREATE VIEW vw_faculty_monthly_report AS
SELECT
    u.id AS user_id,
    CONCAT(u.first_name, ' ', u.last_name) AS faculty_name,
    u.employee_id,
    d.name AS department_name,
    YEAR(al.attendance_date) AS year,
    MONTH(al.attendance_date) AS month,
    COUNT(CASE WHEN al.status = 'PRESENT' THEN 1 END) AS days_present,
    COUNT(CASE WHEN al.status = 'ABSENT' THEN 1 END) AS days_absent,
    COUNT(CASE WHEN al.status = 'LATE' THEN 1 END) AS days_late,
    COUNT(CASE WHEN al.status = 'ON_LEAVE' THEN 1 END) AS days_on_leave,
    ROUND(AVG(al.working_hours), 2) AS avg_working_hours
FROM users u
JOIN departments d ON u.department_id = d.id
LEFT JOIN attendance_logs al ON al.user_id = u.id
WHERE u.role = 'FACULTY' AND u.is_active = TRUE
GROUP BY u.id, u.first_name, u.last_name, u.employee_id, d.name, YEAR(al.attendance_date), MONTH(al.attendance_date);

-- ============================================================
-- STORED PROCEDURE: Mark absent faculty after cutoff time
-- ============================================================
DELIMITER //
CREATE PROCEDURE sp_mark_absent_faculty(IN cutoff_hour INT)
BEGIN
    INSERT INTO attendance_logs (user_id, attendance_date, status, created_at)
    SELECT
        u.id,
        CURDATE(),
        'ABSENT',
        NOW()
    FROM users u
    WHERE u.role = 'FACULTY'
      AND u.is_active = TRUE
      AND u.id NOT IN (
          SELECT al.user_id
          FROM attendance_logs al
          WHERE al.attendance_date = CURDATE()
      )
      AND HOUR(NOW()) >= cutoff_hour;
END //
DELIMITER ;
