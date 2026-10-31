-- ============================================================
-- V3: Fix leave_requests.leave_type enum member case
-- ============================================================
-- Problem
--   schema.sql declared the member as lowercase 'earned' while the JPA enum
--   LeaveType.EARNED is uppercase. MySQL stores the column case-insensitively
--   (utf8mb4_0900_ai_ci) and always returns the DEFINED member string
--   ('earned'), but Hibernate 6 reads EnumType.STRING values with case-sensitive
--   java.lang.Enum.valueOf() (EnumJavaType.fromString). The result:
--     INSERT of EARNED leaves succeeds, then ANY read of that row throws
--     IllegalArgumentException ("No enum constant ...LeaveType.earned")
--     -> HTTP 500 on /leave/my-leaves, /leave/admin/*, inbox, admin dashboards.
--   The frontend offers "Earned Leave" (value EARNED), so the first application
--   of an earned leave poisons those reads.
--
-- Safety
--   * Metadata-only ALTER: no rows are reset, dropped, truncated or deleted.
--   * Zero existing rows use the affected member (verified live:
--     CASUAL x2, SICK x1, no 'earned' rows), so no stored value can change.
--   * The member keeps position 4; MySQL 8.0 DDL for this ALTER is atomic —
--     on any error the table is left untouched.
--
-- Verification (run after applying):
--   SHOW CREATE TABLE leave_requests;              -- expect 'EARNED' uppercase
--   SELECT leave_type, COUNT(*) FROM leave_requests GROUP BY leave_type;
--                                                     -- expect same counts as before
-- ============================================================

ALTER TABLE leave_requests
    MODIFY leave_type ENUM('CASUAL', 'MEDICAL', 'OD', 'EARNED', 'SICK', 'MATERNITY', 'PATERNITY') NOT NULL;
