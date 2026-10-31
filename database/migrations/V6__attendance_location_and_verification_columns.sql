-- ============================================================
-- V6: Add Geolocation and Face Verification Columns to Attendance Logs
-- ============================================================

ALTER TABLE attendance_logs
    ADD COLUMN latitude DECIMAL(10, 7) NULL AFTER late_minutes,
    ADD COLUMN longitude DECIMAL(10, 7) NULL AFTER latitude,
    ADD COLUMN location_accuracy DECIMAL(8, 2) NULL AFTER longitude,
    ADD COLUMN distance_from_college DECIMAL(10, 2) NULL AFTER location_accuracy,
    ADD COLUMN face_confidence DECIMAL(5, 2) NULL AFTER distance_from_college,
    ADD COLUMN verification_status VARCHAR(50) NULL DEFAULT 'UNVERIFIED' AFTER face_confidence;
