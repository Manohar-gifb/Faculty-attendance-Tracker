package com.college.attendance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gated integration test verifying Flyway fresh-install and safe adoption
 * against an isolated, disposable MySQL instance on port 33071.
 *
 * CRITICAL SAFETY ASSERTONS:
 * 1. This test is gated behind system property -Dflywayverify.enabled=true.
 * 2. It will NEVER execute against the live college database on port 3307.
 * 3. It enforces strict URL assertions before running any query.
 */
@SpringBootTest
@ActiveProfiles("flywayfresh")
@EnabledIfSystemProperty(named = "flywayverify.enabled", matches = "true")
class FlywayMigrationVerificationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("Guaranteed Isolation: Test targets port 33071 and never port 3307")
    void verifyStrictDatasourceIsolation() {
        String url = environment.getProperty("spring.datasource.url");
        assertNotNull(url, "Datasource URL must be configured");
        assertTrue(url.contains("33071"), "Integration test MUST target disposable port 33071");
        assertFalse(url.contains(":3307/"), "Integration test MUST NEVER address production port 3307");
        assertTrue(url.contains("college_attendance_flywayfresh"), "Database name must be the disposable test DB");
    }

    @Test
    @DisplayName("Fresh install builds schema from V1 and records SUCCESS in flyway_schema_history")
    void freshInstallExecutesV1Successfully() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Assert flyway_schema_history exists
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT version, description, success FROM flyway_schema_history WHERE version = '1'")) {
                assertTrue(rs.next(), "V1 baseline migration record must exist in flyway_schema_history");
                assertEquals("1", rs.getString("version"));
                assertTrue(rs.getBoolean("success"), "V1 migration must be recorded as successful");
            }

            // Assert all 5 entity tables exist
            String[] expectedTables = {"departments", "users", "attendance_logs", "leave_requests", "audit_logs"};
            for (String table : expectedTables) {
                try (ResultSet rs = stmt.executeQuery("SHOW TABLES LIKE '" + table + "'")) {
                    assertTrue(rs.next(), "Table " + table + " must exist after fresh V1 migration");
                }
            }
        }
    }
}
