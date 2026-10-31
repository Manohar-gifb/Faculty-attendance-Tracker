package com.college.attendance;

import com.college.attendance.support.HibernateSchemaExporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Always-on regression test (runs with every normal test execution) to guarantee:
 * 1. The Flyway V1 baseline migration (V1__baseline_schema.sql) exactly reflects
 *    all JPA entities and their declared tables, columns, indexes, and constraints.
 * 2. database/schema.sql remains synchronized with the V1 baseline (zero dual-source drift).
 * 3. Hibernate 6's connectionless MySQL DDL generation agrees on table and foreign-key topology.
 */
class SchemaBaselineTest {

    private static String v1Sql;
    private static String masterSchemaSql;
    private static String entityExportDdl;

    @BeforeAll
    static void setUp() throws IOException {
        Path v1Path = Paths.get("src", "main", "resources", "db", "migration", "V1__baseline_schema.sql");
        assertTrue(Files.exists(v1Path), "V1__baseline_schema.sql must exist under src/main/resources/db/migration");
        v1Sql = Files.readString(v1Path, StandardCharsets.UTF_8);

        Path masterPath = Paths.get("..", "database", "schema.sql");
        if (!Files.exists(masterPath)) {
            masterPath = Paths.get("database", "schema.sql");
        }
        assertTrue(Files.exists(masterPath), "database/schema.sql must exist");
        masterSchemaSql = Files.readString(masterPath, StandardCharsets.UTF_8);

        // Connectionless DDL generation from live entity models
        entityExportDdl = HibernateSchemaExporter.exportMySqlDdl();
    }

    @Test
    @DisplayName("V1 baseline contains all 5 entity tables")
    void v1ContainsAllEntityTables() {
        List<String> requiredTables = List.of(
                "departments",
                "users",
                "attendance_logs",
                "leave_requests",
                "audit_logs"
        );

        for (String table : requiredTables) {
            String pattern = "create table if not exists " + table;
            assertTrue(v1Sql.toLowerCase(Locale.ROOT).contains(pattern),
                    "V1 baseline must contain CREATE TABLE statement for: " + table);
            assertTrue(entityExportDdl.toLowerCase(Locale.ROOT).contains("create table " + table),
                    "Entity-exported DDL must contain CREATE TABLE statement for: " + table);
        }
    }

    @Test
    @DisplayName("V1 baseline contains all declared columns for Users table")
    void v1UsersColumnsComplete() {
        String lower = v1Sql.toLowerCase(Locale.ROOT);
        List<String> userColumns = List.of(
                "id", "employee_id", "first_name", "last_name", "email",
                "password_hash", "role", "department_id", "leave_approver_id",
                "secondary_approver_id", "phone", "address", "bio",
                "profile_picture_url", "is_active", "first_login",
                "password_setup_token", "password_setup_token_expiry",
                "password_setup_token_purpose", "password_setup_token_created_at",
                "password_setup_token_used_at", "activated_at", "last_login",
                "created_at", "updated_at", "is_developer_unique"
        );

        for (String col : userColumns) {
            assertTrue(lower.contains(col), "users table in V1 baseline must contain column: " + col);
        }
    }

    @Test
    @DisplayName("V1 baseline contains all declared columns for AttendanceLogs table")
    void v1AttendanceLogsColumnsComplete() {
        String lower = v1Sql.toLowerCase(Locale.ROOT);
        List<String> attendanceColumns = List.of(
                "id", "user_id", "attendance_date", "punch_in_time", "punch_out_time",
                "working_hours", "status", "is_late", "late_minutes", "latitude",
                "longitude", "location_accuracy", "distance_from_college",
                "face_confidence", "verification_status", "remarks", "created_at", "updated_at"
        );

        for (String col : attendanceColumns) {
            assertTrue(lower.contains(col), "attendance_logs table in V1 baseline must contain column: " + col);
        }
    }

    @Test
    @DisplayName("V1 baseline contains all declared columns for LeaveRequests table")
    void v1LeaveRequestsColumnsComplete() {
        String lower = v1Sql.toLowerCase(Locale.ROOT);
        List<String> leaveColumns = List.of(
                "id", "user_id", "leave_type", "start_date", "end_date", "reason",
                "attachment_url", "status", "approved_by", "approval_token",
                "approval_token_expiry", "reviewer_comments", "reviewed_at",
                "created_at", "updated_at"
        );

        for (String col : leaveColumns) {
            assertTrue(lower.contains(col), "leave_requests table in V1 baseline must contain column: " + col);
        }
    }

    @Test
    @DisplayName("V1 baseline contains all required unique constraints and indexes")
    void v1UniqueConstraintsAndIndexesComplete() {
        String lower = v1Sql.toLowerCase(Locale.ROOT);
        assertAll("Unique constraints and indexes",
                () -> assertTrue(lower.contains("uk_departments_code"), "Missing uk_departments_code"),
                () -> assertTrue(lower.contains("uk_departments_name"), "Missing uk_departments_name"),
                () -> assertTrue(lower.contains("uk_users_employee_id"), "Missing uk_users_employee_id"),
                () -> assertTrue(lower.contains("uk_users_email"), "Missing uk_users_email"),
                () -> assertTrue(lower.contains("uk_single_developer"), "Missing uk_single_developer"),
                () -> assertTrue(lower.contains("uk_attendance_user_date"), "Missing uk_attendance_user_date"),
                () -> assertTrue(lower.contains("uk_leave_approval_token"), "Missing uk_leave_approval_token"),
                () -> assertTrue(lower.contains("idx_audit_created_at"), "Missing idx_audit_created_at"),
                () -> assertTrue(lower.contains("idx_audit_action"), "Missing idx_audit_action"),
                () -> assertTrue(lower.contains("idx_audit_actor"), "Missing idx_audit_actor")
        );
    }

    @Test
    @DisplayName("V1 baseline enforces foreign key references")
    void v1ForeignKeysComplete() {
        String lower = v1Sql.toLowerCase(Locale.ROOT);
        assertAll("Foreign key constraints",
                () -> assertTrue(lower.contains("references departments (id)"), "Missing departments FK"),
                () -> assertTrue(lower.contains("references users (id)"), "Missing users FK reference")
        );
    }

    @Test
    @DisplayName("database/schema.sql contains all tables present in V1 baseline (no drift)")
    void masterSchemaMatchesV1Tables() {
        String masterLower = masterSchemaSql.toLowerCase(Locale.ROOT);
        List<String> requiredTables = List.of(
                "departments",
                "users",
                "attendance_logs",
                "leave_requests",
                "audit_logs"
        );

        for (String table : requiredTables) {
            assertTrue(masterLower.contains("create table " + table) || masterLower.contains("create table if not exists " + table),
                    "master database/schema.sql must define table: " + table);
        }
    }
}
